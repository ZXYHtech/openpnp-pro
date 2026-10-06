import math
import sys
from jarray import array
from java.awt import BorderLayout, Dimension
from java.awt.event import ActionListener
from java.io import File, FileOutputStream, OutputStreamWriter
from java.lang import Double, Runnable
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

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap
from org.openpnp.corexy.diagnostics import (CoreXYCalibrationPlanner,
                                            CoreXYVisualDiagnostics)


def ensure_directory(directory):
    if not directory.isDirectory() and not directory.mkdirs():
        raise IOError("Cannot create directory: " + directory.getAbsolutePath())


def round_tenth(value):
    return math.floor(value * 10.0 + 0.5) / 10.0


def build_pilot_distances(maximum):
    minimum = max(1.0, min(5.0, maximum * 0.10))
    candidates = [minimum, maximum * 0.12, maximum * 0.25,
                  maximum * 0.45, maximum * 0.70, maximum]
    distances = []
    for candidate in candidates:
        value = round_tenth(candidate)
        if not distances or value - distances[-1] >= 0.1 - 1e-9:
            distances.append(value)
    return distances


def join_values(values, pattern):
    return ", ".join([pattern.format(float(value)) for value in values])


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
    set_float(props, prefix + ".step-modeled-worst.mm", fit.getExpectedWorstMm())


def store_speed_result(props, speed_index, speed, result):
    prefix = "speed.{0}".format(speed_index + 1)
    set_float(props, prefix + ".factor", speed)
    set_location(props, prefix + ".configured-reference",
                 result.getConfiguredReference())
    set_location(props, prefix + ".detected-reference",
                 result.getDetectedReference())
    set_float(props, prefix + ".stationary-noise.mm",
              result.getStationaryVisionNoiseMm())
    set_float(props, prefix + ".vision-detection-floor.mm",
              result.getVisionDetectionFloorMm())
    a_results = list(result.getAResults())
    b_results = list(result.getBResults())
    for direction, values in [("a", a_results), ("b", b_results)]:
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
            props.store(stream, "OpenPnP CoreXY automatic pilot calibration session")
        finally:
            stream.close()


def direction_line(speed, result):
    return (
        "speed {0:.0%} {1} {2:7.1f} mm  correction "
        "({3:+.6f}, {4:+.6f}) mm  magnitude {5:.6f}  scatter {6:.6f}"
    ).format(
        speed, result.getDirection(), result.getVirtualDistanceMm(),
        result.getCorrectionXmm(), result.getCorrectionYmm(),
        result.getMagnitudeMm(), result.getScatterMm())


def write_report(plan, selected_maximum, distances, speeds, results,
                 state, error_text, stamp):
    ensure_directory(reports_dir)
    primary = plan.getPrimaryFiducial()
    lines = [
        "OpenPnP CoreXY Automatic Pilot Calibration Session",
        "",
        "State: " + state,
        "This is an uncompensated diagnostic session. No candidate was applied.",
        "Primary Fiducial: X={0:.6f}, Y={1:.6f}, Z={2:.6f} mm".format(
            primary.getX(), primary.getY(), primary.getZ()),
        "Soft-limit-derived virtual range: {0:.3f} mm".format(
            plan.getAvailableVirtualDistanceMm()),
        "Selected virtual range: {0:.3f} mm; physical X/Y excursion: {1:.3f} mm".format(
            selected_maximum, selected_maximum * 0.5),
        "Distances: " + join_values(distances, "{0:.1f}"),
        "Explicit speeds: " + join_values(speeds, "{0:.0%}"),
        "Repeats: {0}; settle: {1} ms; stationary captures: {2}".format(
            REPEATS, SETTLE_MS, STATIONARY_CAPTURES),
        "The OpenPnP configured axis tolerance is not used.",
        ""
    ]
    for result_index, result in enumerate(results):
        speed = speeds[result_index]
        lines.append("Speed {0:.0%}".format(speed))
        for direction in list(result.getAResults()):
            lines.append(direction_line(speed, direction))
        for direction in list(result.getBResults()):
            lines.append(direction_line(speed, direction))
        lines.append("")
    if error_text:
        lines.extend(["Error:", error_text, ""])
    report_file = File(reports_dir, "auto-calibration-pilot-" + stamp + ".txt")
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
        self.dialog = JDialog(None, "CoreXY Automatic Pilot Calibration", False)
        self.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE)
        self.label = JLabel("Preparing automatic pilot calibration...")
        self.bar = JProgressBar(0, 100)
        self.bar.setStringPainted(True)
        self.stop_button = JButton("Request stop")

        class StopAction(ActionListener):
            def actionPerformed(inner_self, event):
                stop_requested.set(True)
                self.stop_button.setEnabled(False)
                self.label.setText("Stop requested; waiting for the current return...")

        self.stop_button.addActionListener(StopAction())
        panel = JPanel(BorderLayout(8, 8))
        panel.add(self.label, BorderLayout.NORTH)
        panel.add(self.bar, BorderLayout.CENTER)
        panel.add(self.stop_button, BorderLayout.SOUTH)
        panel.setPreferredSize(Dimension(560, 90))
        self.dialog.setContentPane(panel)
        self.dialog.pack()
        self.dialog.setLocationRelativeTo(None)

    def show(self):
        self.dialog.setVisible(True)

    def close(self):
        self.dialog.dispose()

    def update(self, speed_index, speed, stage, completed, per_speed_total):
        overall = speed_index * per_speed_total + completed
        percent = int(round(100.0 * overall / max(1, self.total_steps)))
        stage_text = str(stage)
        parts = stage_text.split("|")
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
        label = labels.get(stage_name, stage_text)
        if len(parts) >= 4 and stage_name.startswith("distance-"):
            label += " at {0} mm ({1}/{2})".format(parts[3], parts[1], parts[2])
        display = "Speed {0:.0%}: {1}  {2}/{3}".format(
            speed, label, overall, self.total_steps)

        class UpdateUi(Runnable):
            def run(inner_self):
                self.label.setText(display)
                self.bar.setValue(percent)

        SwingUtilities.invokeLater(UpdateUi())


class CombinedProgressListener(CoreXYVisualDiagnostics.ProgressListener):
    def __init__(self, window, stop_requested, speed_index, speed):
        self.window = window
        self.stop_requested = stop_requested
        self.speed_index = speed_index
        self.speed = speed

    def onProgress(self, stage, completed, total):
        self.window.update(self.speed_index, self.speed, stage, completed, total)
        if self.stop_requested.get():
            raise RuntimeError("Calibration stop requested by the user.")


def initialize_properties(plan, selected_maximum, distances, speeds, stamp):
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("source", "external-auto-calibration-pilot")
    props.setProperty("created", stamp)
    props.setProperty("state", "running")
    props.setProperty("completed-speed-count", "0")
    props.setProperty("candidate.generated", "false")
    props.setProperty("compensation.applied", "false")
    props.setProperty("camera.name", str(plan.getCameraName()))
    props.setProperty("axis.x.name", str(plan.getXAxisName()))
    props.setProperty("axis.y.name", str(plan.getYAxisName()))
    set_location(props, "primary", plan.getPrimaryFiducial())
    set_float(props, "path-safety-margin.mm", plan.getPathSafetyMarginMm())
    set_float(props, "available-virtual-distance.mm",
              plan.getAvailableVirtualDistanceMm())
    set_float(props, "recommended-virtual-distance.mm",
              plan.getRecommendedVirtualDistanceMm())
    set_float(props, "selected-virtual-distance.mm", selected_maximum)
    set_float(props, "maximum-physical-axis-excursion.mm", selected_maximum * 0.5)
    props.setProperty("distance.count", str(len(distances)))
    for index, distance in enumerate(distances):
        set_float(props, "distance.{0}.mm".format(index + 1), distance)
    props.setProperty("speed.count", str(len(speeds)))
    for index, speed in enumerate(speeds):
        set_float(props, "speed.{0}.factor".format(index + 1), speed)
    props.setProperty("repeats", str(REPEATS))
    props.setProperty("settle.ms", str(SETTLE_MS))
    props.setProperty("stationary-captures", str(STATIONARY_CAPTURES))
    return props


def start_session(plan, selected_maximum, distances, speeds):
    ensure_directory(diagnostics_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped_file = File(
        diagnostics_dir, "auto-calibration-pilot-" + stamp + ".properties")
    latest_file = File(diagnostics_dir, "latest-auto-calibration-pilot.properties")
    props = initialize_properties(plan, selected_maximum, distances, speeds, stamp)
    write_properties(props, stamped_file, latest_file)

    per_speed_total = 2 + STATIONARY_CAPTURES + len(distances) * REPEATS * 4
    total_steps = len(speeds) * per_speed_total
    stop_requested = AtomicBoolean(False)
    window = ProgressWindow(total_steps, stop_requested)
    window.show()

    def run_session():
        results = []
        state = "failed"
        error_text = ""
        try:
            for speed_index, speed in enumerate(speeds):
                listener = CombinedProgressListener(
                    window, stop_requested, speed_index, speed)
                result = CoreXYVisualDiagnostics.measureDistanceScan(
                    array(distances, 'd'), REPEATS, speed, SETTLE_MS,
                    STATIONARY_CAPTURES, listener)
                results.append(result)
                store_speed_result(props, speed_index, speed, result)
                props.setProperty("completed-speed-count", str(len(results)))
                write_properties(props, stamped_file, latest_file)
            state = "completed"
        except Exception as error:
            error_text = str(error)
            state = "cancelled" if stop_requested.get() else "failed"
        props.setProperty("state", state)
        props.setProperty("completed", SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()))
        if error_text:
            props.setProperty("error", error_text)
        write_properties(props, stamped_file, latest_file)
        report_file = write_report(
            plan, selected_maximum, distances, speeds, results,
            state, error_text, stamp)

        class ShowResult(Runnable):
            def run(inner_self):
                window.close()
                if state == "completed":
                    message = (
                        "Automatic pilot calibration completed.\n\n"
                        "No candidate was generated or applied.\n"
                        "Completed speed stages: {0}/{1}\n\n"
                        "Data: {2}\nReport: {3}"
                    ).format(len(results), len(speeds),
                             stamped_file.getAbsolutePath(),
                             report_file.getAbsolutePath())
                    message_type = JOptionPane.INFORMATION_MESSAGE
                else:
                    message = (
                        "Automatic pilot calibration {0}.\n\n"
                        "Completed speed stages retained: {1}/{2}\n"
                        "Error: {3}\n\nData: {4}\nReport: {5}"
                    ).format(state, len(results), len(speeds), error_text,
                             stamped_file.getAbsolutePath(),
                             report_file.getAbsolutePath())
                    message_type = JOptionPane.WARNING_MESSAGE
                JOptionPane.showMessageDialog(
                    None, message, "CoreXY Automatic Pilot Calibration",
                    message_type)

        SwingUtilities.invokeLater(ShowResult())

    UiUtils.submitUiMachineTask(run_session)


def prepare_session():
    status = str(CoreXYScriptBootstrap.status())
    if "enabled=true" in status:
        raise RuntimeError(
            "Disable CoreXY compensation before automatic pilot calibration.")
    plan = CoreXYCalibrationPlanner.discoverDefaultPlan()
    recommended = plan.getRecommendedVirtualDistanceMm()
    input_value = JOptionPane.showInputDialog(
        None,
        "Enter the maximum virtual A/B distance in mm.\n\n"
        "The detected safe recommendation is {0:.1f} mm.\n"
        "The corresponding maximum physical X/Y excursion is half this value.\n"
        "You may enter a smaller value to avoid fixtures or other obstacles.".format(
            recommended),
        "{0:.1f}".format(recommended))
    if input_value is None:
        return
    selected_maximum = float(str(input_value).strip())
    if (not Double.isFinite(selected_maximum) or selected_maximum < 10.0
            or selected_maximum > recommended + 1e-9):
        raise ValueError(
            "Maximum virtual distance must be between 10.0 and {0:.1f} mm.".format(
                recommended))
    distances = build_pilot_distances(selected_maximum)
    speeds = [float(value) for value in list(plan.getSpeedFactors())]
    primary = plan.getPrimaryFiducial()
    half = selected_maximum * 0.5
    message = (
        "This uncompensated pilot session will make real machine movements.\n\n"
        "Virtual range: {0:.1f} mm; maximum X/Y excursion: {1:.1f} mm\n"
        "XY envelope: X {2:.1f} to {3:.1f}, Y {4:.1f} to {5:.1f} mm\n"
        "Distances: {6} mm\n"
        "Explicit speeds: {7}\n"
        "Repeats: {8}; visual return measurements: {9}\n\n"
        "Soft limits cannot detect feeders, fixtures, boards or loose objects.\n"
        "Raise the head, clear the complete envelope and keep emergency stop ready.\n\n"
        "Type RUN to continue."
    ).format(
        selected_maximum, half,
        primary.getX() - half, primary.getX() + half,
        primary.getY() - half, primary.getY() + half,
        join_values(distances, "{0:.1f}"),
        join_values(speeds, "{0:.0%}"),
        REPEATS, len(distances) * len(speeds) * REPEATS * 4)
    confirmation = JOptionPane.showInputDialog(None, message, "")
    if confirmation is None or str(confirmation).strip().upper() != "RUN":
        return
    start_session(plan, selected_maximum, distances, speeds)


try:
    prepare_session()
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Automatic Pilot Calibration Error",
        JOptionPane.ERROR_MESSAGE)
    raise
