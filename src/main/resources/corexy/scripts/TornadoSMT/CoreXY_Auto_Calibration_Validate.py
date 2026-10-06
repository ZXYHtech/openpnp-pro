import sys
from jarray import array
from java.awt import BorderLayout, Dimension
from java.awt.event import ActionListener
from java.io import (File, FileInputStream, FileOutputStream,
                     OutputStreamWriter)
from java.lang import Runnable
from java.text import SimpleDateFormat
from java.util import ArrayList, Date, Properties
from java.util.concurrent.atomic import AtomicBoolean
from javax.swing import (JButton, JDialog, JLabel, JOptionPane, JPanel,
                         JProgressBar, SwingUtilities)
from org.openpnp.model import Configuration
from org.openpnp.util import UiUtils


SPEEDS = [0.40, 0.70, 0.90]
DISTANCES = [15.0, 28.0, 34.0, 60.0, 180.0, 270.0]
REPEATS = 3
SETTLE_MS = 500
STATIONARY_CAPTURES = 7

scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
candidates_dir = File(runtime_dir, "candidates")
diagnostics_dir = File(runtime_dir, "diagnostics")
reports_dir = File(runtime_dir, "reports")
candidate_file = File(
    candidates_dir, "latest-auto-calibration-surface.properties")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap
from org.openpnp.corexy.diagnostics import (CoreXYCalibrationAnalysis,
                                            CoreXYCalibrationPlanner,
                                            CoreXYCalibrationSurface,
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
    for index, sample in enumerate(samples):
        sample_prefix = prefix + ".sample.{0}".format(index + 1)
        set_float(props, sample_prefix + ".correction.x.mm",
                  sample.getCorrectionXmm())
        set_float(props, sample_prefix + ".correction.y.mm",
                  sample.getCorrectionYmm())
        set_location(props, sample_prefix + ".positive",
                     sample.getPositiveLocation())
        set_location(props, sample_prefix + ".negative",
                     sample.getNegativeLocation())


def store_metrics(props, prefix, metrics):
    set_float(props, prefix + ".rms.mm", metrics.getRmsMm())
    set_float(props, prefix + ".p95.mm", metrics.getP95Mm())
    set_float(props, prefix + ".maximum.mm", metrics.getMaximumMm())
    set_float(props, prefix + ".maximum-worsening.mm",
              metrics.getMaximumWorseningMm())


def metrics_line(label, metrics):
    return (
        "{0}: RMS {1:.6f}, P95 {2:.6f}, maximum {3:.6f}, "
        "worst worsening {4:.6f} mm"
    ).format(
        label, metrics.getRmsMm(), metrics.getP95Mm(),
        metrics.getMaximumMm(), metrics.getMaximumWorseningMm())


def write_properties(props, stamped, latest):
    for output in [stamped, latest]:
        stream = FileOutputStream(output)
        try:
            props.store(stream, "OpenPnP CoreXY held-out validation")
        finally:
            stream.close()


class ProgressWindow(object):
    def __init__(self, total_steps, stop_requested):
        self.total_steps = total_steps
        self.stop_requested = stop_requested
        self.dialog = JDialog(None, "CoreXY Held-out Validation", False)
        self.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE)
        self.label = JLabel("Preparing held-out validation...")
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

    def update(self, speed_index, speed, stage, completed, per_speed_total):
        overall = speed_index * per_speed_total + completed
        percent = int(round(100.0 * overall / max(1, self.total_steps)))
        parts = str(stage).split("|")
        labels = {
            "reference": "Centering the Primary Fiducial",
            "vision-noise": "Measuring stationary vision noise",
            "distance-a": "Measuring held-out A returns",
            "distance-b": "Measuring held-out B returns",
            "return-reference": "Returning to the Primary Fiducial",
            "complete": "Speed stage complete",
            "failed": "Measurement failed"
        }
        label = labels.get(parts[0], str(stage))
        if len(parts) >= 4 and parts[0].startswith("distance-"):
            label += " at {0} mm ({1}/{2})".format(
                parts[3], parts[1], parts[2])
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
        self.window.update(
            self.speed_index, self.speed, stage, completed, total)
        if self.stop_requested.get():
            raise RuntimeError("Validation stop requested by the user.")


def add_observations(target, speed, result):
    floor = result.getVisionDetectionFloorMm()
    for direction, values in [
            ("A", list(result.getAResults())),
            ("B", list(result.getBResults()))]:
        for value in values:
            target.add(CoreXYCalibrationAnalysis.Observation(
                direction, speed, value.getVirtualDistanceMm(),
                value.getCorrectionXmm(), value.getCorrectionYmm(),
                value.getScatterMm(), floor))


def store_stage(props, stage_index, speed, result):
    prefix = "stage.{0}".format(stage_index + 1)
    set_float(props, prefix + ".speed-factor", speed)
    set_float(props, prefix + ".vision-detection-floor.mm",
              result.getVisionDetectionFloorMm())
    set_float(props, prefix + ".stationary-noise.mm",
              result.getStationaryVisionNoiseMm())
    for direction, values in [
            ("a", list(result.getAResults())),
            ("b", list(result.getBResults()))]:
        props.setProperty(prefix + "." + direction + ".distance.count",
                          str(len(values)))
        for index, value in enumerate(values):
            store_direction(
                props,
                prefix + "." + direction + ".distance.{0}".format(index + 1),
                value)


def start_validation(plan, fit, gain, source_props):
    ensure_directory(diagnostics_dir)
    ensure_directory(reports_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped = File(
        diagnostics_dir, "auto-calibration-validation-" + stamp + ".properties")
    latest = File(
        diagnostics_dir, "latest-auto-calibration-validation.properties")
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("source", "external-auto-calibration-held-out-validation")
    props.setProperty("state", "running")
    props.setProperty("created", stamp)
    props.setProperty("source-candidate", candidate_file.getCanonicalPath())
    props.setProperty("candidate-model", required(source_props, "model.kind"))
    set_float(props, "candidate-gain", gain)
    target = float(required(source_props, "target.radial-tolerance.mm"))
    maximum_guard = float(required(
        source_props, "target.maximum-residual-guard.mm"))
    set_float(props, "target.radial-tolerance.mm", target)
    set_float(props, "target.maximum-residual-guard.mm", maximum_guard)
    props.setProperty("compensation.applied", "false")
    props.setProperty("stage.count", str(len(SPEEDS)))
    write_properties(props, stamped, latest)

    per_speed_total = 2 + STATIONARY_CAPTURES + len(DISTANCES) * REPEATS * 4
    total_steps = per_speed_total * len(SPEEDS)
    stop_requested = AtomicBoolean(False)
    window = ProgressWindow(total_steps, stop_requested)
    window.show()

    def run_validation():
        results = []
        observations = ArrayList()
        state = "failed"
        error_text = ""
        validation = None
        target_passed = False
        passed = False
        try:
            for speed_index, speed in enumerate(SPEEDS):
                listener = CombinedProgressListener(
                    window, stop_requested, speed_index, speed)
                result = CoreXYVisualDiagnostics.measureDistanceScan(
                    array(DISTANCES, 'd'), REPEATS, speed, SETTLE_MS,
                    STATIONARY_CAPTURES, listener)
                results.append(result)
                add_observations(observations, speed, result)
                store_stage(props, speed_index, speed, result)
                props.setProperty("completed-stage-count", str(len(results)))
                write_properties(props, stamped, latest)
            validation = CoreXYCalibrationSurface.validate(
                fit, observations, gain)
            target_passed = (
                validation.getCandidateMetrics().getP95Mm() <= target and
                validation.getCandidateMetrics().getMaximumMm() <=
                maximum_guard)
            passed = validation.isPassed() and target_passed
            state = "passed" if passed else "failed-validation"
            props.setProperty("validation.passed",
                              str(passed).lower())
            props.setProperty("validation.relative-safeguards-passed",
                              str(validation.isPassed()).lower())
            props.setProperty("validation.user-target-passed",
                              str(target_passed).lower())
            store_metrics(props, "validation.baseline",
                          validation.getBaselineMetrics())
            store_metrics(props, "validation.candidate",
                          validation.getCandidateMetrics())
            store_metrics(props, "validation.a.baseline",
                          validation.getABaselineMetrics())
            store_metrics(props, "validation.a.candidate",
                          validation.getACandidateMetrics())
            store_metrics(props, "validation.b.baseline",
                          validation.getBBaselineMetrics())
            store_metrics(props, "validation.b.candidate",
                          validation.getBCandidateMetrics())
        except Exception as error:
            error_text = str(error)
            state = "cancelled" if stop_requested.get() else "failed"
        props.setProperty("state", state)
        props.setProperty(
            "completed", SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()))
        if error_text:
            props.setProperty("error", error_text)
        write_properties(props, stamped, latest)

        lines = [
            "OpenPnP CoreXY Held-out Candidate Validation",
            "",
            "State: " + state,
            "No compensation was applied during measurement.",
            "Candidate gain: {0:.0%}".format(gain),
            "User 2D radial target: {0:.6f} mm; maximum guard: "
            "{1:.6f} mm".format(target, maximum_guard),
            "Speeds: " + ", ".join(["{0:.0%}".format(v) for v in SPEEDS]),
            "Distances: " + ", ".join(["{0:.1f}".format(v) for v in DISTANCES]),
            "Observations are excluded from the fitted calibration surface.",
            ""
        ]
        if validation is not None:
            lines.extend([
                metrics_line("All baseline", validation.getBaselineMetrics()),
                metrics_line("All candidate", validation.getCandidateMetrics()),
                metrics_line("A baseline", validation.getABaselineMetrics()),
                metrics_line("A candidate", validation.getACandidateMetrics()),
                metrics_line("B baseline", validation.getBBaselineMetrics()),
                metrics_line("B candidate", validation.getBCandidateMetrics()),
                "",
                "Relative safeguards passed: " +
                str(validation.isPassed()).lower(),
                "User target passed: " + str(target_passed).lower(),
                "Validation passed: " + str(passed).lower()
            ])
        if error_text:
            lines.extend(["", "Error: " + error_text])
        lines.extend([
            "",
            "This result does not enable or write production compensation."
        ])
        report = File(
            reports_dir, "auto-calibration-validation-" + stamp + ".txt")
        writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
        try:
            writer.write("\r\n".join(lines))
        finally:
            writer.close()

        class ShowResult(Runnable):
            def run(inner_self):
                window.close()
                message = "\n".join(lines) + "\n\nData: " + \
                    stamped.getAbsolutePath() + "\nReport: " + \
                    report.getAbsolutePath()
                message_type = (JOptionPane.INFORMATION_MESSAGE
                                if state == "passed"
                                else JOptionPane.WARNING_MESSAGE)
                JOptionPane.showMessageDialog(
                    None, message, "CoreXY Held-out Validation", message_type)

        SwingUtilities.invokeLater(ShowResult())

    UiUtils.submitUiMachineTask(run_validation)


def prepare_validation():
    status = str(CoreXYScriptBootstrap.status())
    if "enabled=true" in status:
        raise RuntimeError(
            "Disable CoreXY compensation before held-out validation.")
    if not candidate_file.isFile():
        raise IOError("No automatic calibration surface candidate was found.")
    props = load_properties(candidate_file)
    if required(props, "state") != "fitted-not-applied":
        raise ValueError("The latest surface candidate is not ready.")
    index = int(required(props, "recommended-candidate.index"))
    if index <= 0:
        raise ValueError("No candidate passed offline eligibility checks.")
    prefix = "candidate.{0}".format(index)
    if required(props, prefix + ".eligible") != "true":
        raise ValueError("The recommended candidate is not eligible.")
    if required(props, prefix + ".policy-eligible") != "true":
        raise ValueError(
            "The recommended candidate does not meet the user residual target.")
    gain = float(required(props, prefix + ".gain"))
    fit = CoreXYCalibrationSurface.fitFiles(
        required(props, "source-pilot"),
        required(props, "source-refinement"))
    plan = CoreXYCalibrationPlanner.discoverDefaultPlan()
    if max(DISTANCES) > plan.getRecommendedVirtualDistanceMm() + 1e-6:
        raise RuntimeError("Current soft-limit plan is smaller than validation range.")
    primary = plan.getPrimaryFiducial()
    half = max(DISTANCES) * 0.5
    message = (
        "Held-out visual validation will make real uncompensated movements.\n\n"
        "Candidate gain evaluated mathematically: {0:.0%}\n"
        "Speeds: {1}\nDistances: {2} mm\n"
        "Grid cells: {3}; visual return measurements: {4}\n"
        "XY envelope: X {5:.1f} to {6:.1f}, Y {7:.1f} to {8:.1f} mm\n\n"
        "No candidate compensation will be applied during this test.\n"
        "The measured points were excluded from model fitting.\n"
        "Soft limits cannot detect fixtures or loose objects.\n"
        "Clear the complete envelope and keep emergency stop ready.\n\n"
        "Type VALIDATE to continue."
    ).format(
        gain,
        ", ".join(["{0:.0%}".format(v) for v in SPEEDS]),
        ", ".join(["{0:.1f}".format(v) for v in DISTANCES]),
        len(SPEEDS) * len(DISTANCES),
        len(SPEEDS) * len(DISTANCES) * REPEATS * 4,
        primary.getX() - half, primary.getX() + half,
        primary.getY() - half, primary.getY() + half)
    confirmation = JOptionPane.showInputDialog(None, message, "")
    if confirmation is None or str(confirmation).strip().upper() != "VALIDATE":
        return
    start_validation(plan, fit, gain, props)


try:
    prepare_validation()
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Held-out Validation Error",
        JOptionPane.ERROR_MESSAGE)
    raise
