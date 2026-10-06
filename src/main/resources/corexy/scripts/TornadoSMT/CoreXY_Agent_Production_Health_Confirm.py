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
config_file = File(runtime_dir, "config/corexy.properties")
latest_health_file = File(
    runtime_dir, "diagnostics/latest-production-health.properties")
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


def status_values(text):
    result = {}
    for line in str(text).splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


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


def write_properties(props, stamped, latest):
    for output in [stamped, latest]:
        stream = FileOutputStream(output)
        try:
            props.store(stream, "OpenPnP CoreXY production health confirmation")
        finally:
            stream.close()


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


def same_path(left, right):
    return File(str(left)).getCanonicalPath().lower() == \
        File(str(right)).getCanonicalPath().lower()


def find_reference_point(reference, direction, speed, distance):
    stage_count = int(required(reference, "stage.count"))
    for stage_index in range(stage_count):
        stage_prefix = "stage.{0}".format(stage_index + 1)
        stage_speed = float(required(
            reference, stage_prefix + ".speed-factor"))
        if abs(stage_speed - speed) > 0.000001:
            continue
        count = int(required(
            reference,
            stage_prefix + "." + direction.lower() + ".distance.count"))
        for distance_index in range(count):
            prefix = (stage_prefix + "." + direction.lower() +
                      ".distance.{0}".format(distance_index + 1))
            point_distance = float(required(
                reference, prefix + ".virtual-distance.mm"))
            if abs(point_distance - distance) <= 0.000001:
                return {
                    "prefix": prefix,
                    "magnitude": float(required(
                        reference, prefix + ".magnitude.mm")),
                    "scatter": float(required(
                        reference, prefix + ".scatter.mm"))
                }
    raise ValueError("The warning cell was not found in production evidence.")


class ProgressWindow(object):
    def __init__(self, total_steps, stop_requested, direction, speed, distance):
        self.total_steps = total_steps
        self.stop_requested = stop_requested
        self.dialog = JDialog(None, "CoreXY Health Result Confirmation", False)
        self.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE)
        self.label = JLabel(
            "Preparing {0}, {1:.0%}, {2:.1f} mm confirmation...".format(
                direction, speed, distance))
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
        panel.setPreferredSize(Dimension(620, 90))
        self.dialog.setContentPane(panel)
        self.dialog.pack()
        self.dialog.setLocationRelativeTo(None)

    def show(self):
        self.dialog.setVisible(True)

    def close(self):
        self.dialog.dispose()

    def update(self, stage, completed, total):
        percent = int(round(100.0 * completed / max(1, self.total_steps)))
        parts = str(stage).split("|")
        labels = {
            "reference": "Centering the Primary Fiducial",
            "vision-noise": "Measuring stationary vision noise",
            "distance-a": "Measuring the A companion cell",
            "distance-b": "Measuring the B companion cell",
            "return-reference": "Returning to the Primary Fiducial",
            "complete": "Confirmation complete",
            "failed": "Confirmation failed"
        }
        display = labels.get(parts[0], str(stage))

        class UpdateUi(Runnable):
            def run(inner_self):
                self.label.setText(display)
                self.bar.setValue(percent)

        SwingUtilities.invokeLater(UpdateUi())


class ConfirmationProgressListener(CoreXYVisualDiagnostics.ProgressListener):
    def __init__(self, window, stop_requested):
        self.window = window
        self.stop_requested = stop_requested

    def onProgress(self, stage, completed, total):
        self.window.update(stage, completed, total)
        if self.stop_requested.get():
            raise RuntimeError(
                "Production health confirmation stop requested by the user.")


def start_confirmation(config, health, reference_file, reference,
                       direction, speed, distance, reference_point):
    ensure_directory(diagnostics_dir)
    ensure_directory(reports_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped = File(
        diagnostics_dir,
        "production-health-confirmation-" + stamp + ".properties")
    latest = File(
        diagnostics_dir, "latest-production-health-confirmation.properties")
    config_hash_before = sha256(config_file)
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("source", "external-production-health-confirmation")
    props.setProperty("state", "running")
    props.setProperty("created", stamp)
    props.setProperty("source-health", latest_health_file.getCanonicalPath())
    props.setProperty("source-production-reference",
                      reference_file.getCanonicalPath())
    props.setProperty("source-production-config",
                      config_file.getCanonicalPath())
    props.setProperty("source-production-config.sha256", config_hash_before)
    props.setProperty("production.promoted-at",
                      required(config, "production.promoted-at"))
    props.setProperty("warning.direction", direction)
    set_float(props, "warning.speed-factor", speed)
    set_float(props, "warning.distance.mm", distance)
    set_float(props, "warning.reference.magnitude.mm",
              reference_point["magnitude"])
    set_float(props, "warning.previous.magnitude.mm",
              float(required(
                  health, "current.maximum-worsening.result.mm")))
    write_properties(props, stamped, latest)

    total_steps = 2 + STATIONARY_CAPTURES + REPEATS * 4
    stop_requested = AtomicBoolean(False)
    window = ProgressWindow(
        total_steps, stop_requested, direction, speed, distance)
    window.show()

    def run_confirmation():
        state = "failed"
        error_text = ""
        lines = []
        try:
            listener = ConfirmationProgressListener(window, stop_requested)
            result = CoreXYVisualDiagnostics.measureCompensatedDistanceScan(
                array([distance], 'd'), REPEATS, speed, SETTLE_MS,
                STATIONARY_CAPTURES, listener)
            a_result = list(result.getAResults())[0]
            b_result = list(result.getBResults())[0]
            target = a_result if direction == "A" else b_result
            companion = b_result if direction == "A" else a_result
            store_direction(props, "target", target)
            store_direction(props, "companion", companion)
            props.setProperty("companion.direction",
                              "B" if direction == "A" else "A")
            set_float(props, "vision-detection-floor.mm",
                      result.getVisionDetectionFloorMm())
            set_float(props, "stationary-noise.mm",
                      result.getStationaryVisionNoiseMm())

            reference_magnitude = reference_point["magnitude"]
            previous_magnitude = float(required(
                health, "current.maximum-worsening.result.mm"))
            current_magnitude = target.getMagnitudeMm()
            degradation = current_magnitude - reference_magnitude
            repeat_change = abs(current_magnitude - previous_magnitude)
            vision_floor = max(
                float(required(health, "health.vision-floor.mm")),
                result.getVisionDetectionFloorMm(),
                result.getStationaryVisionNoiseMm())
            pass_guard = vision_floor * 3.0
            warning_guard = vision_floor * 6.0
            config_hash_after = sha256(config_file)
            runtime_after = status_values(CoreXYScriptBootstrap.status())
            integrity_ok = (
                config_hash_before == config_hash_after and
                runtime_after.get("enabled") == "true" and
                runtime_after.get("dry-run") == "false" and
                same_path(runtime_after.get("config", ""), config_file))

            if not integrity_ok:
                classification = "FAIL"
                state = "failed-health"
            elif degradation <= pass_guard:
                classification = "PASS_CLEARED"
                state = "passed"
            elif degradation <= warning_guard and repeat_change <= pass_guard:
                classification = "WARNING_CONFIRMED"
                state = "warning"
            elif degradation <= warning_guard:
                classification = "WARNING_UNCONFIRMED"
                state = "warning"
            else:
                classification = "FAIL"
                state = "failed-health"

            props.setProperty("confirmation.result", classification)
            set_float(props, "confirmation.degradation.mm", degradation)
            set_float(props, "confirmation.repeat-change.mm", repeat_change)
            set_float(props, "confirmation.vision-floor.mm", vision_floor)
            set_float(props, "confirmation.pass-guard.mm", pass_guard)
            set_float(props, "confirmation.warning-guard.mm", warning_guard)
            props.setProperty("integrity.passed", str(integrity_ok).lower())
            props.setProperty("source-production-config.sha256-after",
                              config_hash_after)

            lines = [
                "OpenPnP CoreXY Production Health Result Confirmation",
                "",
                "Result: " + classification,
                "Target: {0}, speed {1:.0%}, distance {2:.1f} mm".format(
                    direction, speed, distance),
                "Accepted reference: {0:.6f} mm".format(reference_magnitude),
                "Full health check: {0:.6f} mm".format(previous_magnitude),
                "Seven-repeat confirmation: {0:.6f} mm".format(
                    current_magnitude),
                "Degradation from reference: {0:+.6f} mm".format(degradation),
                "Change from full health measurement: {0:.6f} mm".format(
                    repeat_change),
                "Vision floor: {0:.6f} mm; PASS guard: {1:.6f} mm; "
                "WARNING guard: {2:.6f} mm".format(
                    vision_floor, pass_guard, warning_guard),
                "Target vector: ({0:+.6f}, {1:+.6f}) mm; scatter {2:.6f} mm".format(
                    target.getCorrectionXmm(), target.getCorrectionYmm(),
                    target.getScatterMm()),
                "Companion {0}: magnitude {1:.6f} mm; scatter {2:.6f} mm".format(
                    "B" if direction == "A" else "A",
                    companion.getMagnitudeMm(), companion.getScatterMm()),
                "Production configuration integrity: " +
                ("PASS" if integrity_ok else "FAIL"),
                "",
                "No candidate, model, review or production configuration was changed."
            ]
        except Exception as error:
            error_text = str(error)
            state = "cancelled" if stop_requested.get() else "failed"
            lines = [
                "OpenPnP CoreXY Production Health Result Confirmation",
                "",
                "State: " + state,
                "Error: " + error_text,
                "",
                "No production configuration was changed."
            ]
        finally:
            props.setProperty("state", state)
            props.setProperty(
                "completed", SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()))
            try:
                final_hash = sha256(config_file)
                props.setProperty(
                    "source-production-config.sha256-after", final_hash)
                props.setProperty(
                    "integrity.config-unchanged",
                    str(final_hash == config_hash_before).lower())
            except Exception as hash_error:
                props.setProperty("integrity.hash-error", str(hash_error))
            if error_text:
                props.setProperty("error", error_text)
            write_properties(props, stamped, latest)
            try:
                Configuration.get().getMachine().setEnabled(False)
            except Exception as disable_error:
                lines.extend(["", "Machine disable error: " + str(disable_error)])

        report = File(
            reports_dir,
            "production-health-confirmation-" + stamp + ".txt")
        writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
        try:
            writer.write("\r\n".join(lines))
        finally:
            writer.close()

        class ShowResult(Runnable):
            def run(inner_self):
                window.close()
                message = "\n".join(lines) + \
                    "\n\nMachine disabled after confirmation." + \
                    "\n\nData: " + stamped.getAbsolutePath() + \
                    "\nReport: " + report.getAbsolutePath()
                message_type = (JOptionPane.INFORMATION_MESSAGE
                                if state == "passed"
                                else JOptionPane.WARNING_MESSAGE)
                JOptionPane.showMessageDialog(
                    None, message, "CoreXY Health Result Confirmation",
                    message_type)

        SwingUtilities.invokeLater(ShowResult())

    UiUtils.submitUiMachineTask(run_confirmation)


def prepare_confirmation():
    for path in [config_file, latest_health_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    config = load_properties(config_file)
    health = load_properties(latest_health_file)
    health_result = required(health, "health.result")
    health_state = required(health, "state")
    valid_health_state = (
        (health_result == "WARNING" and health_state == "warning") or
        (health_result == "FAIL" and health_state == "failed-health"))
    if not valid_health_state:
        raise ValueError(
            "The latest completed health check is not a WARNING or FAIL result.")
    if required(config, "production.persistence") != "persistent" or \
            required(config, "enabled") != "true" or \
            required(config, "dry-run") != "false":
        raise ValueError("Persistent production compensation is not active.")
    if required(health, "production.promoted-at") != \
            required(config, "production.promoted-at"):
        raise ValueError("The health result belongs to another production revision.")
    if sha256(config_file).lower() != \
            required(health, "source-production-config.sha256").lower():
        raise ValueError("Production configuration changed after the warning.")

    status = status_values(CoreXYScriptBootstrap.status())
    if status.get("configured") != "true" or \
            status.get("enabled") != "true" or \
            status.get("dry-run") != "false" or \
            not same_path(status.get("config", ""), config_file):
        raise RuntimeError("The Agent is not using the formal production config.")
    if status.get("homed") != "true" or status.get("homing-bypass") != "false":
        raise RuntimeError("Enable and Home the machine before confirmation.")
    if status.get("A-state") != "0" or status.get("B-state") != "0":
        raise RuntimeError("Home again so the initial A/B states are both zero.")
    machine = Configuration.get().getMachine()
    if not machine.isEnabled() or not machine.isHomed():
        raise RuntimeError("Enable and Home the machine before confirmation.")

    direction = required(
        health, "current.maximum-worsening.direction").upper()
    if direction not in ["A", "B"]:
        raise ValueError("Unknown warning direction: " + direction)
    speed = float(required(
        health, "current.maximum-worsening.speed-factor"))
    distance = float(required(
        health, "current.maximum-worsening.distance.mm"))
    model_maximum = float(required(
        config, "model." + direction.lower() + ".maximum-distance.mm"))
    if distance > model_maximum + 0.000001:
        raise ValueError("The warning distance exceeds the production model.")

    reference_file = File(required(
        health, "source-production-reference"))
    if not reference_file.isFile():
        raise IOError("Production reference not found: " +
                      reference_file.getAbsolutePath())
    reference = load_properties(reference_file)
    reference_point = find_reference_point(
        reference, direction, speed, distance)

    primary = machine.getDefaultHead().getCalibrationPrimaryFiducialLocation()
    half = distance * 0.5
    message = (
        "Confirm the latest production health {0}\n\n"
        "Target: {1}, speed {2:.0%}, virtual distance {3:.1f} mm\n"
        "Accepted reference: {4:.6f} mm\n"
        "Health measurement: {5:.6f} mm\n"
        "Repeats: {6}; stationary captures: {7}\n"
        "XY envelope: X {8:.1f} to {9:.1f}, Y {10:.1f} to {11:.1f} mm\n\n"
        "The companion motor direction at the same speed and distance is also\n"
        "measured for comparison. No compensation configuration is changed.\n"
        "The machine will be disabled automatically when the test ends.\n\n"
        "Soft limits cannot detect fixtures or loose objects.\n"
        "Clear the complete envelope and keep emergency stop ready.\n\n"
        "Type CONFIRM HEALTH to continue."
    ).format(
        health_result, direction, speed, distance, reference_point["magnitude"],
        float(required(health, "current.maximum-worsening.result.mm")),
        REPEATS, STATIONARY_CAPTURES,
        primary.getX() - half, primary.getX() + half,
        primary.getY() - half, primary.getY() + half)
    confirmation = JOptionPane.showInputDialog(None, message, "")
    if confirmation is None or \
            str(confirmation).strip().upper() != "CONFIRM HEALTH":
        return
    start_confirmation(
        config, health, reference_file, reference,
        direction, speed, distance, reference_point)


try:
    prepare_confirmation()
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Health Result Confirmation Error",
        JOptionPane.ERROR_MESSAGE)
    raise
