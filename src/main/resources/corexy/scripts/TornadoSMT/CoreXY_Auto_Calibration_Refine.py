import sys
from jarray import array
from java.awt import BorderLayout, Dimension
from java.awt.event import ActionListener
from java.io import (File, FileInputStream, FileOutputStream,
                     OutputStreamWriter)
from java.lang import Runnable
from java.text import SimpleDateFormat
from java.util import Date, Properties
from java.util.concurrent.atomic import AtomicBoolean
from javax.swing import (JButton, JDialog, JLabel, JOptionPane, JPanel,
                         JProgressBar, SwingUtilities)
from org.openpnp.model import Configuration
from org.openpnp.util import UiUtils


REPEATS = 3
SETTLE_MS = 500
STATIONARY_CAPTURES = 7

scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
diagnostics_dir = File(runtime_dir, "diagnostics")
reports_dir = File(runtime_dir, "reports")
analysis_file = File(
    diagnostics_dir, "latest-auto-calibration-analysis.properties")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap
from org.openpnp.corexy.diagnostics import (CoreXYCalibrationPlanner,
                                            CoreXYVisualDiagnostics)


def ensure_directory(directory):
    if not directory.isDirectory() and not directory.mkdirs():
        raise IOError("Cannot create directory: " + directory.getAbsolutePath())


def load_properties(path):
    props = Properties()
    stream = FileInputStream(path)
    try:
        props.load(stream)
    finally:
        stream.close()
    return props


def required(props, key):
    value = props.getProperty(key)
    if value is None or not str(value).strip():
        raise ValueError("Missing property: " + key)
    return str(value).strip()


def required_float(props, key):
    return float(required(props, key))


def add_point(groups, speed, distance):
    key = round(float(speed), 6)
    if key not in groups:
        groups[key] = set()
    groups[key].add(round(float(distance), 6))


def point_key(speed, distance):
    return "{0:.6f}|{1:.6f}".format(float(speed), float(distance))


def collect_existing_points(pilot, refinement_file):
    measured = set()
    speeds = set()
    for speed_index in range(1, int(required(pilot, "speed.count")) + 1):
        speed = required_float(
            pilot, "speed.{0}.factor".format(speed_index))
        speeds.add(round(speed, 6))
        count = int(required(
            pilot, "speed.{0}.a.distance.count".format(speed_index)))
        for distance_index in range(1, count + 1):
            distance = required_float(
                pilot, "speed.{0}.a.distance.{1}.virtual-distance.mm".format(
                    speed_index, distance_index))
            measured.add(point_key(speed, distance))

    visited = set()
    current = refinement_file
    while current is not None and current.isFile():
        canonical = current.getCanonicalPath()
        if canonical in visited:
            raise ValueError("Refinement source chain contains a cycle.")
        visited.add(canonical)
        props = load_properties(current)
        if required(props, "state") != "completed":
            raise ValueError("A source refinement session is incomplete.")
        count = int(required(props, "stage.count"))
        for stage_index in range(1, count + 1):
            speed = required_float(
                props, "stage.{0}.speed-factor".format(stage_index))
            speeds.add(round(speed, 6))
            distance_count = int(required(
                props,
                "stage.{0}.a.distance.count".format(stage_index)))
            for distance_index in range(1, distance_count + 1):
                distance = required_float(
                    props,
                    "stage.{0}.a.distance.{1}.virtual-distance.mm".format(
                        stage_index, distance_index))
                measured.add(point_key(speed, distance))
        previous = props.getProperty("source-previous-refinement")
        current = File(str(previous)) if previous else None
    return measured, speeds


def build_groups(analysis, pilot):
    groups = {}
    new_speeds = set()
    count = int(required(analysis, "refinement.count"))
    previous_path = analysis.getProperty("source-refinement")
    previous_file = File(str(previous_path)) if previous_path else None
    measured, existing_speeds = collect_existing_points(pilot, previous_file)
    for index in range(1, count + 1):
        prefix = "refinement.{0}".format(index)
        speed = required_float(analysis, prefix + ".speed-factor")
        distance = required_float(analysis, prefix + ".distance.mm")
        kind = required(analysis, prefix + ".kind")
        if point_key(speed, distance) in measured and kind != "repeat":
            continue
        add_point(groups, speed, distance)
        if round(speed, 6) not in existing_speeds:
            new_speeds.add(round(speed, 6))

    distances = [
        required_float(pilot, "distance.{0}.mm".format(index))
        for index in range(1, int(required(pilot, "distance.count")) + 1)
    ]
    minimum = min(distances)
    maximum = max(distances)
    for speed in new_speeds:
        add_point(groups, speed, minimum)
        add_point(groups, speed, maximum)
    return [(speed, sorted(groups[speed])) for speed in sorted(groups.keys())]


def set_float(props, key, value):
    props.setProperty(key, "{0:.9f}".format(float(value)))


def set_location(props, prefix, location):
    set_float(props, prefix + ".x.mm", location.getX())
    set_float(props, prefix + ".y.mm", location.getY())
    set_float(props, prefix + ".z.mm", location.getZ())


def store_direction(props, prefix, result):
    set_float(props, prefix + ".virtual-distance.mm",
              result.getVirtualDistanceMm())
    set_float(props, prefix + ".correction.x.mm", result.getCorrectionXmm())
    set_float(props, prefix + ".correction.y.mm", result.getCorrectionYmm())
    set_float(props, prefix + ".magnitude.mm", result.getMagnitudeMm())
    set_float(props, prefix + ".scatter.mm", result.getScatterMm())
    samples = list(result.getSamples())
    props.setProperty(prefix + ".sample.count", str(len(samples)))
    for sample_index, sample in enumerate(samples):
        sample_prefix = prefix + ".sample.{0}".format(sample_index + 1)
        set_location(props, sample_prefix + ".positive",
                     sample.getPositiveLocation())
        set_location(props, sample_prefix + ".negative",
                     sample.getNegativeLocation())
        set_float(props, sample_prefix + ".correction.x.mm",
                  sample.getCorrectionXmm())
        set_float(props, sample_prefix + ".correction.y.mm",
                  sample.getCorrectionYmm())


def store_fit(props, prefix, fit):
    set_float(props, prefix + ".threshold.mm", fit.getThresholdMm())
    set_float(props, prefix + ".correction.x.mm", fit.getCorrectionXmm())
    set_float(props, prefix + ".correction.y.mm", fit.getCorrectionYmm())
    set_float(props, prefix + ".baseline-worst.mm", fit.getBaselineWorstMm())
    set_float(props, prefix + ".step-modeled-worst.mm",
              fit.getExpectedWorstMm())


def store_speed_result(props, stage_index, speed, result):
    prefix = "stage.{0}".format(stage_index + 1)
    set_float(props, prefix + ".speed-factor", speed)
    set_location(props, prefix + ".configured-reference",
                 result.getConfiguredReference())
    set_location(props, prefix + ".detected-reference",
                 result.getDetectedReference())
    set_float(props, prefix + ".stationary-noise.mm",
              result.getStationaryVisionNoiseMm())
    set_float(props, prefix + ".vision-detection-floor.mm",
              result.getVisionDetectionFloorMm())
    for direction, values in [
            ("a", list(result.getAResults())),
            ("b", list(result.getBResults()))]:
        props.setProperty(prefix + "." + direction + ".distance.count",
                          str(len(values)))
        for distance_index, value in enumerate(values):
            store_direction(
                props,
                prefix + "." + direction + ".distance.{0}".format(
                    distance_index + 1),
                value)
    store_fit(props, prefix + ".a.step-fit", result.getAFit())
    store_fit(props, prefix + ".b.step-fit", result.getBFit())


def write_properties(props, stamped_file, latest_file):
    for output_file in [stamped_file, latest_file]:
        stream = FileOutputStream(output_file)
        try:
            props.store(stream,
                        "OpenPnP CoreXY automatic adaptive refinement session")
        finally:
            stream.close()


def direction_line(speed, result):
    return (
        "speed {0:.1%} {1} {2:7.1f} mm  correction "
        "({3:+.6f}, {4:+.6f}) mm  magnitude {5:.6f}  scatter {6:.6f}"
    ).format(
        speed, result.getDirection(), result.getVirtualDistanceMm(),
        result.getCorrectionXmm(), result.getCorrectionYmm(),
        result.getMagnitudeMm(), result.getScatterMm())


def write_report(groups, results, state, error_text, stamp):
    ensure_directory(reports_dir)
    lines = [
        "OpenPnP CoreXY Automatic Adaptive Refinement",
        "",
        "State: " + state,
        "Source analysis: " + analysis_file.getCanonicalPath(),
        "This is an uncompensated diagnostic session. No candidate was applied.",
        ""
    ]
    for result_index, result in enumerate(results):
        speed = groups[result_index][0]
        lines.append("Speed {0:.1%}".format(speed))
        for direction in list(result.getAResults()):
            lines.append(direction_line(speed, direction))
        for direction in list(result.getBResults()):
            lines.append(direction_line(speed, direction))
        lines.append("")
    if error_text:
        lines.extend(["Error:", error_text, ""])
    report_file = File(
        reports_dir, "auto-calibration-refinement-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report_file), "UTF-8")
    try:
        writer.write("\r\n".join(lines))
    finally:
        writer.close()
    return report_file


class ProgressWindow(object):
    def __init__(self, total_steps, stop_requested):
        self.total_steps = total_steps
        self.stop_requested = stop_requested
        self.dialog = JDialog(
            None, "CoreXY Automatic Adaptive Refinement", False)
        self.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE)
        self.label = JLabel("Preparing adaptive refinement...")
        self.bar = JProgressBar(0, 100)
        self.bar.setStringPainted(True)
        self.stop_button = JButton("Request stop")

        class StopAction(ActionListener):
            def actionPerformed(inner_self, event):
                stop_requested.set(True)
                self.stop_button.setEnabled(False)
                self.label.setText(
                    "Stop requested; waiting for the current return...")

        self.stop_button.addActionListener(StopAction())
        panel = JPanel(BorderLayout(8, 8))
        panel.add(self.label, BorderLayout.NORTH)
        panel.add(self.bar, BorderLayout.CENTER)
        panel.add(self.stop_button, BorderLayout.SOUTH)
        panel.setPreferredSize(Dimension(580, 90))
        self.dialog.setContentPane(panel)
        self.dialog.pack()
        self.dialog.setLocationRelativeTo(None)

    def show(self):
        self.dialog.setVisible(True)

    def close(self):
        self.dialog.dispose()

    def update(self, base, speed, stage, completed):
        overall = base + completed
        percent = int(round(100.0 * overall / max(1, self.total_steps)))
        parts = str(stage).split("|")
        stage_name = parts[0]
        labels = {
            "reference": "Centering the Primary Fiducial",
            "vision-noise": "Measuring stationary vision noise",
            "distance-a": "Measuring A motor-state returns",
            "distance-b": "Measuring B motor-state returns",
            "return-reference": "Returning to the Primary Fiducial",
            "complete": "Speed stage complete",
            "failed": "Measurement failed"
        }
        label = labels.get(stage_name, str(stage))
        if len(parts) >= 4 and stage_name.startswith("distance-"):
            label += " at {0} mm ({1}/{2})".format(
                parts[3], parts[1], parts[2])
        display = "Speed {0:.1%}: {1}  {2}/{3}".format(
            speed, label, overall, self.total_steps)

        class UpdateUi(Runnable):
            def run(inner_self):
                self.label.setText(display)
                self.bar.setValue(percent)

        SwingUtilities.invokeLater(UpdateUi())


class CombinedProgressListener(CoreXYVisualDiagnostics.ProgressListener):
    def __init__(self, window, stop_requested, base, speed):
        self.window = window
        self.stop_requested = stop_requested
        self.base = base
        self.speed = speed

    def onProgress(self, stage, completed, total):
        self.window.update(self.base, self.speed, stage, completed)
        if self.stop_requested.get():
            raise RuntimeError("Calibration stop requested by the user.")


def initialize_properties(groups, analysis, pilot, stamp):
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("source", "external-auto-calibration-refinement")
    props.setProperty("source-analysis", analysis_file.getCanonicalPath())
    props.setProperty("source-pilot",
                      required(analysis, "source-pilot"))
    previous = analysis.getProperty("source-refinement")
    if previous:
        props.setProperty("source-previous-refinement", str(previous))
    props.setProperty("created", stamp)
    props.setProperty("state", "running")
    props.setProperty("completed-stage-count", "0")
    props.setProperty("candidate.generated", "false")
    props.setProperty("compensation.applied", "false")
    props.setProperty("recommended-model",
                      required(analysis, "recommended-model"))
    props.setProperty("stage.count", str(len(groups)))
    for stage_index, group in enumerate(groups):
        prefix = "stage.{0}".format(stage_index + 1)
        speed, distances = group
        set_float(props, prefix + ".speed-factor", speed)
        props.setProperty(prefix + ".planned-distance.count",
                          str(len(distances)))
        for distance_index, distance in enumerate(distances):
            set_float(
                props,
                prefix + ".planned-distance.{0}.mm".format(distance_index + 1),
                distance)
    set_float(props, "selected-virtual-distance.mm",
              required_float(pilot, "selected-virtual-distance.mm"))
    set_float(props, "primary.x.mm", required_float(pilot, "primary.x.mm"))
    set_float(props, "primary.y.mm", required_float(pilot, "primary.y.mm"))
    set_float(props, "primary.z.mm", required_float(pilot, "primary.z.mm"))
    return props


def start_refinement(groups, analysis, pilot):
    ensure_directory(diagnostics_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped_file = File(
        diagnostics_dir, "auto-calibration-refinement-" + stamp + ".properties")
    latest_file = File(
        diagnostics_dir, "latest-auto-calibration-refinement.properties")
    props = initialize_properties(groups, analysis, pilot, stamp)
    write_properties(props, stamped_file, latest_file)

    totals = [
        2 + STATIONARY_CAPTURES + len(distances) * REPEATS * 4
        for speed, distances in groups
    ]
    total_steps = sum(totals)
    stop_requested = AtomicBoolean(False)
    window = ProgressWindow(total_steps, stop_requested)
    window.show()

    def run_refinement():
        results = []
        state = "failed"
        error_text = ""
        base = 0
        try:
            for stage_index, group in enumerate(groups):
                speed, distances = group
                listener = CombinedProgressListener(
                    window, stop_requested, base, speed)
                result = CoreXYVisualDiagnostics.measureDistanceScan(
                    array(distances, 'd'), REPEATS, speed, SETTLE_MS,
                    STATIONARY_CAPTURES, listener)
                results.append(result)
                store_speed_result(props, stage_index, speed, result)
                props.setProperty("completed-stage-count", str(len(results)))
                write_properties(props, stamped_file, latest_file)
                base += totals[stage_index]
            state = "completed"
        except Exception as error:
            error_text = str(error)
            state = "cancelled" if stop_requested.get() else "failed"
        props.setProperty("state", state)
        props.setProperty(
            "completed", SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()))
        if error_text:
            props.setProperty("error", error_text)
        write_properties(props, stamped_file, latest_file)
        report_file = write_report(
            groups, results, state, error_text, stamp)

        class ShowResult(Runnable):
            def run(inner_self):
                window.close()
                if state == "completed":
                    message = (
                        "Adaptive refinement completed.\n\n"
                        "No candidate was generated or applied.\n"
                        "Completed speed stages: {0}/{1}\n\n"
                        "Data: {2}\nReport: {3}"
                    ).format(
                        len(results), len(groups),
                        stamped_file.getAbsolutePath(),
                        report_file.getAbsolutePath())
                    message_type = JOptionPane.INFORMATION_MESSAGE
                else:
                    message = (
                        "Adaptive refinement {0}.\n\n"
                        "Completed speed stages retained: {1}/{2}\n"
                        "Error: {3}\n\nData: {4}\nReport: {5}"
                    ).format(
                        state, len(results), len(groups), error_text,
                        stamped_file.getAbsolutePath(),
                        report_file.getAbsolutePath())
                    message_type = JOptionPane.WARNING_MESSAGE
                JOptionPane.showMessageDialog(
                    None, message, "CoreXY Automatic Adaptive Refinement",
                    message_type)

        SwingUtilities.invokeLater(ShowResult())

    UiUtils.submitUiMachineTask(run_refinement)


def prepare_refinement():
    status = str(CoreXYScriptBootstrap.status())
    if "enabled=true" in status:
        raise RuntimeError(
            "Disable CoreXY compensation before adaptive refinement.")
    if not analysis_file.isFile():
        raise IOError("No automatic calibration analysis was found.")
    analysis = load_properties(analysis_file)
    if required(analysis, "state") != "analyzed-not-measured":
        raise ValueError("The latest analysis is not ready for refinement.")
    pilot_file = File(required(analysis, "source-pilot"))
    if not pilot_file.isFile():
        raise IOError("The source pilot session no longer exists.")
    pilot = load_properties(pilot_file)
    if required(pilot, "state") != "completed":
        raise ValueError("The source pilot session is not complete.")
    plan = CoreXYCalibrationPlanner.discoverDefaultPlan()
    primary = plan.getPrimaryFiducial()
    for axis_name, current_value in [
            ("X", primary.getX()), ("Y", primary.getY()),
            ("Z", primary.getZ())]:
        saved_value = required_float(
            pilot, "primary." + axis_name.lower() + ".mm")
        if abs(current_value - saved_value) > 0.01:
            raise RuntimeError(
                "Primary Fiducial {0} changed after the pilot session.".format(
                    axis_name))

    groups = build_groups(analysis, pilot)
    if not groups:
        raise RuntimeError(
            "No additional refinement points are required by this analysis.")
    maximum = max([
        distance for speed, distances in groups for distance in distances
    ])
    if maximum > plan.getRecommendedVirtualDistanceMm() + 1e-6:
        raise RuntimeError(
            "Current soft-limit plan is smaller than the refinement range.")
    half = maximum * 0.5
    group_lines = "\n".join([
        "{0:.1%}: {1} mm".format(
            speed, ", ".join(["{0:.1f}".format(value)
                              for value in distances]))
        for speed, distances in groups
    ])
    cell_count = sum([len(distances) for speed, distances in groups])
    message = (
        "Adaptive refinement will make real uncompensated movements.\n\n"
        "Recommended model: {0}\n"
        "Speed/distance groups:\n{1}\n\n"
        "Grid cells: {2}; visual return measurements: {3}\n"
        "XY envelope: X {4:.1f} to {5:.1f}, Y {6:.1f} to {7:.1f} mm\n\n"
        "New speed layers include minimum and maximum distance anchors.\n"
        "Soft limits cannot detect fixtures or loose objects.\n"
        "Clear the complete envelope and keep emergency stop ready.\n\n"
        "Type REFINE to continue."
    ).format(
        required(analysis, "recommended-model"), group_lines,
        cell_count, cell_count * REPEATS * 4,
        primary.getX() - half, primary.getX() + half,
        primary.getY() - half, primary.getY() + half)
    confirmation = JOptionPane.showInputDialog(None, message, "")
    if confirmation is None or str(confirmation).strip().upper() != "REFINE":
        return
    start_refinement(groups, analysis, pilot)


try:
    prepare_refinement()
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Automatic Adaptive Refinement Error",
        JOptionPane.ERROR_MESSAGE)
    raise
