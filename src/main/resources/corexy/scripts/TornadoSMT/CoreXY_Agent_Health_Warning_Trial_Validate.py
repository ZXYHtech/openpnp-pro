import math
import sys
from jarray import array, zeros
from java.awt import BorderLayout, Dimension
from java.awt.event import ActionListener
from java.io import (File, FileInputStream, FileOutputStream,
                     OutputStreamWriter)
from java.lang import Runnable
from java.security import MessageDigest
from java.text import SimpleDateFormat
from java.util import Date, Properties
from java.util.concurrent.atomic import AtomicBoolean
from javax.swing import (JButton, JDialog, JLabel, JOptionPane, JPanel,
                         JProgressBar, SwingUtilities)
from org.openpnp.model import Configuration
from org.openpnp.util import UiUtils


REPEATS = 7
SETTLE_MS = 500
STATIONARY_CAPTURES = 9

scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
formal_file = File(runtime_dir, "config/corexy.properties")
analysis_file = File(
    runtime_dir, "diagnostics/latest-health-warning-candidate-analysis.properties")
confirmation_file = File(
    runtime_dir, "diagnostics/latest-production-health-confirmation.properties")
diagnostics_dir = File(runtime_dir, "diagnostics")
reports_dir = File(runtime_dir, "reports")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap
from org.openpnp.corexy.diagnostics import CoreXYVisualDiagnostics


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


def status_values(text):
    result = {}
    for line in str(text).splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def same_path(left, right):
    return File(str(left)).getCanonicalPath().lower() == \
        File(str(right)).getCanonicalPath().lower()


def sha256(path):
    digest = MessageDigest.getInstance("SHA-256")
    stream = FileInputStream(path)
    try:
        buffer = zeros(8192, 'b')
        while True:
            count = stream.read(buffer)
            if count < 0:
                break
            digest.update(buffer, 0, count)
    finally:
        stream.close()
    return "".join(["{0:02x}".format((value + 256) % 256)
                    for value in digest.digest()])


def write_properties(props, stamped, latest):
    for path in [stamped, latest]:
        stream = FileOutputStream(path)
        try:
            props.store(stream, "CoreXY health-warning transient trial validation")
        finally:
            stream.close()


def store_direction(props, prefix, result):
    set_float(props, prefix + ".correction.x.mm", result.getCorrectionXmm())
    set_float(props, prefix + ".correction.y.mm", result.getCorrectionYmm())
    set_float(props, prefix + ".magnitude.mm", result.getMagnitudeMm())
    set_float(props, prefix + ".scatter.mm", result.getScatterMm())
    samples = list(result.getSamples())
    props.setProperty(prefix + ".sample.count", str(len(samples)))
    for index, sample in enumerate(samples, 1):
        sample_prefix = prefix + ".sample.{0}".format(index)
        set_float(props, sample_prefix + ".correction.x.mm",
                  sample.getCorrectionXmm())
        set_float(props, sample_prefix + ".correction.y.mm",
                  sample.getCorrectionYmm())


class ProgressWindow(object):
    def __init__(self, total, stop_requested, direction, speed, distance):
        self.total = total
        self.stop_requested = stop_requested
        self.direction = direction
        self.dialog = JDialog(
            None, "CoreXY Health Warning Trial Validation", False)
        self.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE)
        self.label = JLabel(
            "Preparing {0}, {1:.0%}, {2:.1f} mm...".format(
                direction, speed, distance))
        self.bar = JProgressBar(0, 100)
        self.bar.setStringPainted(True)
        self.stop_button = JButton("Request stop")

        class StopAction(ActionListener):
            def actionPerformed(inner_self, event):
                stop_requested.set(True)
                self.stop_button.setEnabled(False)
                self.label.setText("Stop requested; completing current return...")

        self.stop_button.addActionListener(StopAction())
        panel = JPanel(BorderLayout(8, 8))
        panel.add(self.label, BorderLayout.NORTH)
        panel.add(self.bar, BorderLayout.CENTER)
        panel.add(self.stop_button, BorderLayout.SOUTH)
        panel.setPreferredSize(Dimension(620, 90))
        self.dialog.setContentPane(panel)
        self.dialog.pack()
        self.dialog.setLocationRelativeTo(None)

    def show(self):
        self.dialog.setVisible(True)

    def close(self):
        self.dialog.dispose()

    def update(self, stage, completed):
        labels = {
            "reference": "Centering the Primary Fiducial",
            "vision-noise": "Measuring stationary vision noise",
            "distance-a": ("Measuring target A returns" if
                           self.direction == "A" else
                           "Measuring companion A returns"),
            "distance-b": ("Measuring target B returns" if
                           self.direction == "B" else
                           "Measuring companion B returns"),
            "return-reference": "Returning to the Primary Fiducial",
            "complete": "Validation complete",
            "failed": "Validation failed"
        }
        display = labels.get(str(stage).split("|")[0], str(stage))
        percent = int(round(100.0 * completed / max(1, self.total)))

        class UpdateUi(Runnable):
            def run(inner_self):
                self.label.setText(display)
                self.bar.setValue(percent)

        SwingUtilities.invokeLater(UpdateUi())


class TrialProgressListener(CoreXYVisualDiagnostics.ProgressListener):
    def __init__(self, window, stop_requested):
        self.window = window
        self.stop_requested = stop_requested

    def onProgress(self, stage, completed, total):
        self.window.update(stage, completed)
        if self.stop_requested.get():
            raise RuntimeError("Trial validation stop requested by the user.")


def start_validation(context):
    ensure_directory(diagnostics_dir)
    ensure_directory(reports_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped = File(
        diagnostics_dir, "health-warning-trial-validation-" + stamp + ".properties")
    latest = File(
        diagnostics_dir, "latest-health-warning-trial-validation.properties")
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("source", "health-warning-transient-trial-validation")
    props.setProperty("state", "running")
    props.setProperty("created", stamp)
    props.setProperty("source-trial", context["active_file"].getCanonicalPath())
    props.setProperty("source-analysis", analysis_file.getCanonicalPath())
    props.setProperty("source-confirmation",
                      confirmation_file.getCanonicalPath())
    props.setProperty("source-production-config", formal_file.getCanonicalPath())
    props.setProperty("source-production-config.sha256", context["formal_hash"])
    props.setProperty("production.promoted-at", context["revision"])
    props.setProperty("warning.direction", context["direction"])
    set_float(props, "warning.speed-factor", context["speed"])
    set_float(props, "warning.distance.mm", context["distance"])
    set_float(props, "trial.gain", context["gain"])
    set_float(props, "production.gain", context["production_gain"])
    write_properties(props, stamped, latest)

    total = 2 + STATIONARY_CAPTURES + REPEATS * 4
    stop_requested = AtomicBoolean(False)
    window = ProgressWindow(
        total, stop_requested, context["direction"], context["speed"],
        context["distance"])
    window.show()

    def run_validation():
        state = "failed"
        lines = []
        error_text = ""
        try:
            listener = TrialProgressListener(window, stop_requested)
            result = CoreXYVisualDiagnostics.measureCompensatedDistanceScan(
                array([context["distance"]], 'd'), REPEATS,
                context["speed"], SETTLE_MS, STATIONARY_CAPTURES, listener)
            a_result = list(result.getAResults())[0]
            b_result = list(result.getBResults())[0]
            target = a_result if context["direction"] == "A" else b_result
            companion = b_result if context["direction"] == "A" else a_result
            store_direction(props, "target", target)
            store_direction(props, "companion", companion)
            props.setProperty("companion.direction",
                              "B" if context["direction"] == "A" else "A")
            set_float(props, "vision-detection-floor.mm",
                      result.getVisionDetectionFloorMm())
            set_float(props, "stationary-noise.mm",
                      result.getStationaryVisionNoiseMm())

            target_magnitude = target.getMagnitudeMm()
            companion_magnitude = companion.getMagnitudeMm()
            floor = max(context["vision_floor"],
                        result.getVisionDetectionFloorMm(),
                        result.getStationaryVisionNoiseMm())
            companion_limit = context["companion_reference"] + floor * 3.0
            runtime_after = status_values(CoreXYScriptBootstrap.status())
            integrity_ok = (
                sha256(formal_file) == context["formal_hash"] and
                runtime_after.get("enabled") == "true" and
                runtime_after.get("dry-run") == "false" and
                same_path(runtime_after.get("config", ""),
                          context["active_file"]))
            companion_ok = companion_magnitude <= companion_limit
            if integrity_ok and companion_ok and \
                    target_magnitude <= context["robust_limit"]:
                classification = "PASS_ROBUST"
                state = "passed"
            elif integrity_ok and companion_ok and \
                    target_magnitude <= context["pass_limit"]:
                classification = "PASS"
                state = "passed"
            elif integrity_ok and companion_ok and target_magnitude <= \
                    context["confirmed"] + floor:
                classification = "INCONCLUSIVE"
                state = "warning"
            else:
                classification = "FAIL"
                state = "failed-validation"

            props.setProperty("validation.result", classification)
            props.setProperty("validation.passed",
                              str(state == "passed").lower())
            props.setProperty("integrity.passed", str(integrity_ok).lower())
            set_float(props, "validation.pass-limit.mm", context["pass_limit"])
            set_float(props, "validation.robust-limit.mm", context["robust_limit"])
            set_float(props, "validation.companion-limit.mm", companion_limit)
            set_float(props, "validation.change-from-production.mm",
                      target_magnitude - context["confirmed"])
            lines = [
                "OpenPnP CoreXY Health Warning Transient Trial Validation", "",
                "Result: " + classification,
                "Trial gain: {0:.0%}; persistence: current process only".format(
                    context["gain"]),
                "Target: {0}, speed {1:.0%}, distance {2:.1f} mm".format(
                    context["direction"], context["speed"], context["distance"]),
                "Accepted reference: {0:.6f} mm".format(context["reference"]),
                "Confirmed {0:.0%} production result: {1:.6f} mm".format(
                    context["production_gain"], context["confirmed"]),
                "Measured trial result: {0:.6f} mm".format(target_magnitude),
                "Change from {0:.0%} production: {1:+.6f} mm".format(
                    context["production_gain"],
                    target_magnitude - context["confirmed"]),
                "PASS / robust limits: {0:.6f} / {1:.6f} mm".format(
                    context["pass_limit"], context["robust_limit"]),
                "Companion magnitude / limit: {0:.6f} / {1:.6f} mm".format(
                    companion_magnitude, companion_limit),
                "Trial runtime and formal configuration integrity: " +
                ("PASS" if integrity_ok else "FAIL"), "",
                "The formal production configuration remains unchanged.",
                "Restart or Restore Formal returns to the persistent "
                "{0:.0%} config.".format(context["production_gain"])
            ]
        except Exception as error:
            error_text = str(error)
            state = "cancelled" if stop_requested.get() else "failed"
            lines = ["OpenPnP CoreXY Health Warning Trial Validation", "",
                     "State: " + state, "Error: " + error_text]
        finally:
            props.setProperty("state", state)
            props.setProperty(
                "completed", SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()))
            props.setProperty("integrity.config-unchanged",
                              str(sha256(formal_file) ==
                                  context["formal_hash"]).lower())
            if error_text:
                props.setProperty("error", error_text)
            write_properties(props, stamped, latest)
            try:
                Configuration.get().getMachine().setEnabled(False)
            except Exception as disable_error:
                lines.extend(["", "Machine disable error: " + str(disable_error)])

        report = File(
            reports_dir, "health-warning-trial-validation-" + stamp + ".txt")
        writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
        try:
            writer.write("\r\n".join(lines))
        finally:
            writer.close()

        class ShowResult(Runnable):
            def run(inner_self):
                window.close()
                JOptionPane.showMessageDialog(
                    None, "\n".join(lines) +
                    "\n\nMachine disabled after validation." +
                    "\n\nData: " + stamped.getAbsolutePath() +
                    "\nReport: " + report.getAbsolutePath(),
                    "CoreXY Health Warning Trial Validation",
                    JOptionPane.INFORMATION_MESSAGE if state == "passed"
                    else JOptionPane.WARNING_MESSAGE)

        SwingUtilities.invokeLater(ShowResult())

    UiUtils.submitUiMachineTask(run_validation)


def prepare_validation():
    for path in [formal_file, analysis_file, confirmation_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    formal_hash = sha256(formal_file)
    formal = load_properties(formal_file)
    analysis = load_properties(analysis_file)
    confirmation = load_properties(confirmation_file)
    status = status_values(CoreXYScriptBootstrap.status())
    active_file = File(status.get("config", ""))
    if not active_file.isFile():
        raise RuntimeError("The active transient configuration cannot be read.")
    active = load_properties(active_file)
    if active.getProperty("trial.kind", "") != "health-warning-gain" or \
            active.getProperty("trial.persistence", "") != "process-only":
        raise RuntimeError("Load the health-warning transient trial first.")
    if status.get("enabled") != "true" or status.get("dry-run") != "false":
        raise RuntimeError("The transient physical trial is not active.")
    if required(active, "trial.source-production-config.sha256").lower() != \
            formal_hash.lower():
        raise RuntimeError("Formal configuration changed after trial loading.")
    machine = Configuration.get().getMachine()
    if not machine.isEnabled() or not machine.isHomed() or \
            status.get("homing-bypass") != "false":
        raise RuntimeError("Enable and Home the machine before validation.")
    if status.get("A-state") != "0" or status.get("B-state") != "0":
        raise RuntimeError("Home again so A/B initial states are both zero.")

    gain = float(required(analysis, "recommended-gain"))
    if abs(gain - float(required(active, "model.gain"))) > 1e-9:
        raise ValueError("Active trial gain is not the recommended gain.")
    context = {
        "formal_hash": formal_hash,
        "active_file": active_file,
        "revision": required(formal, "production.promoted-at"),
        "direction": required(analysis, "warning.direction").upper(),
        "speed": float(required(analysis, "warning.speed-factor")),
        "distance": float(required(analysis, "warning.distance.mm")),
        "gain": gain,
        "production_gain": float(required(analysis, "current-gain")),
        "reference": float(required(
            analysis, "warning.reference.magnitude.mm")),
        "confirmed": float(required(
            analysis, "warning.confirmed.magnitude.mm")),
        "pass_limit": float(required(
            analysis, "warning.target-pass-limit.mm")),
        "robust_limit": float(required(
            analysis, "warning.target-robust-limit.mm")),
        "vision_floor": float(required(
            confirmation, "confirmation.vision-floor.mm")),
        "companion_reference": float(required(
            confirmation, "companion.magnitude.mm"))
    }
    half = context["distance"] * 0.5
    primary = machine.getDefaultHead().getCalibrationPrimaryFiducialLocation()
    message = (
        "Validate the transient {0:.0%} CoreXY candidate\n\n"
        "Target: {1}, speed {2:.0%}, virtual distance {3:.1f} mm\n"
        "Seven target returns plus seven companion returns\n"
        "XY envelope: X {4:.1f} to {5:.1f}, Y {6:.1f} to {7:.1f} mm\n\n"
        "The formal {9:.0%} configuration file will not be changed.\n"
        "The machine will be disabled automatically when complete.\n\n"
        "Clear the full envelope and type VALIDATE {8:.0f}."
    ).format(context["gain"], context["direction"], context["speed"],
             context["distance"], primary.getX() - half,
             primary.getX() + half, primary.getY() - half,
             primary.getY() + half, context["gain"] * 100.0,
             context["production_gain"])
    answer = JOptionPane.showInputDialog(None, message, "")
    expected = "VALIDATE {0:.0f}".format(context["gain"] * 100.0)
    if answer is None or str(answer).strip().upper() != expected:
        return
    start_validation(context)


try:
    prepare_validation()
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Health Warning Trial Validation Error",
        JOptionPane.ERROR_MESSAGE)
    raise
