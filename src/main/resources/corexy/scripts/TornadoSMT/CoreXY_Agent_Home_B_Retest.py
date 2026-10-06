import math
import sys
from jarray import zeros
from java.awt import BorderLayout, Dimension
from java.awt.event import ActionListener
from java.io import (File, FileInputStream, FileOutputStream,
                     OutputStreamWriter)
from java.lang import Runnable, Thread
from java.security import MessageDigest
from java.text import SimpleDateFormat
from java.util import Date, Properties
from java.util.concurrent.atomic import AtomicBoolean
from javax.swing import (JButton, JDialog, JLabel, JOptionPane, JPanel,
                         JProgressBar, SwingUtilities)
from org.openpnp.machine.reference import ReferenceHead, ReferenceMachine
from org.openpnp.machine.reference.axis import ReferenceControllerAxis
from org.openpnp.machine.reference.axis.ReferenceControllerAxis import \
    BacklashCompensationMethod
from org.openpnp.machine.reference.camera import ReferenceCamera
from org.openpnp.model import Configuration, LengthUnit, Location
from org.openpnp.spi.MotionPlanner import CompletionType
from org.openpnp.util import MovableUtils, UiUtils


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
formal_file = File(runtime_dir, "config/corexy.properties")
policy_file = File(runtime_dir, "config/calibration-policy.properties")
diagnostics_dir = File(runtime_dir, "diagnostics")
reports_dir = File(runtime_dir, "reports")
AUTO_HOME_REGRESSION = bool(globals().get(
    "COREXY_PRIMARY_AUTO_HOME_REGRESSION", False))

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap
from org.openpnp.corexy.diagnostics import CoreXYVisualDiagnostics


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
    values = {}
    for line in str(text).splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def ensure_directory(path):
    if not path.isDirectory() and not path.mkdirs():
        raise IOError("Cannot create directory: " + path.getAbsolutePath())


def set_float(props, key, value):
    props.setProperty(key, "{0:.9f}".format(float(value)))


def median(values):
    ordered = sorted([float(value) for value in values])
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) * 0.5


def maximum_radial_scatter(xs, ys, center_x, center_y):
    return max([math.hypot(xs[index] - center_x, ys[index] - center_y)
                for index in range(len(xs))])


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


def write_properties(props, stamped, latest, comment=None, mirrors=None):
    if comment is None:
        comment = "OpenPnP CoreXY Home B-state measurement"
    outputs = [stamped, latest]
    if mirrors:
        outputs.extend(mirrors)
    for output in outputs:
        stream = FileOutputStream(output)
        try:
            props.store(stream, comment)
        finally:
            stream.close()


def comparable_measurement(path, speed, distance, production_revision):
    if not path.isFile():
        return None
    props = load_properties(path)
    try:
        if abs(float(required(props, "test.speed-factor")) - speed) > 0.000001:
            return None
        if abs(float(required(props, "test.distance.mm")) - distance) > 0.001:
            return None
        if props.getProperty("production.revision", "") != production_revision:
            return None
        float(required(props, "result.radial-residual.mm"))
        return props
    except Exception:
        return None


def approach_location(reference, dx, dy):
    return reference.add(Location(
        LengthUnit.Millimeters, dx, dy, 0.0, 0.0))


def select_reachable_distance(camera, reference, preferred, minimum):
    candidates = [preferred]
    for value in [270.0, 240.0, 210.0, 180.0, 150.0, 120.0,
                  90.0, 60.0, 45.0, 30.0, 20.0, 15.0]:
        if value <= preferred + 0.000001 and value >= minimum - 0.000001:
            candidates.append(value)
    candidates = sorted(set(candidates), reverse=True)
    for distance in candidates:
        x_start = approach_location(reference, -distance, 0.0)
        y_start = approach_location(reference, 0.0, -distance)
        if camera.isReachable(x_start) and camera.isReachable(y_start):
            return distance, x_start, y_start
    raise ValueError(
        "No common inward X-/Y- Home retest distance is reachable. "
        "Check the Home fiducial and XY soft limits.")


class ProgressWindow(object):
    def __init__(self, total, stop_requested):
        self.total = total
        self.stop_requested = stop_requested
        self.dialog = JDialog(None, "CoreXY Home B-state Retest", False)
        self.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE)
        self.label = JLabel("Preparing the Home visual reference...")
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
        panel.setPreferredSize(Dimension(650, 90))
        self.dialog.setContentPane(panel)
        self.dialog.pack()
        self.dialog.setLocationRelativeTo(None)

    def show(self):
        self.dialog.setVisible(True)

    def close(self):
        self.dialog.dispose()

    def update(self, text, completed):
        percent = int(round(100.0 * completed / max(1, self.total)))

        class UpdateUi(Runnable):
            def run(inner_self):
                self.label.setText(text)
                self.bar.setValue(percent)

        SwingUtilities.invokeLater(UpdateUi())


def run_retest(machine, head, camera, formal, policy, measurement_mode,
               active_file, active_config, window, stop_requested,
               stamped, latest, peer_latest, report, mirrors=None,
               perform_home=False):
    props = Properties()
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    props.setProperty("schema.version", "1")
    props.setProperty("source", "external-home-b-state-measurement")
    props.setProperty("measurement.mode", measurement_mode)
    props.setProperty("state", "running")
    props.setProperty("created", stamp)
    props.setProperty("reference.role", "validation-only")
    props.setProperty("reference.observable-state", "B")
    props.setProperty("reference.unobservable-state", "A")
    props.setProperty("source-policy", policy_file.getCanonicalPath())
    props.setProperty("source-production-config", formal_file.getCanonicalPath())
    config_hash_before = sha256(formal_file)
    props.setProperty("source-production-config.sha256", config_hash_before)
    props.setProperty("production.revision",
                      required(formal, "production.promoted-at"))
    props.setProperty("production.gain", required(formal, "model.gain"))
    props.setProperty("runtime-config", active_file.getCanonicalPath())
    if measurement_mode.startswith("response-probe-"):
        props.setProperty("probe.axis", required(
            active_config, "trial.probe-axis"))
        props.setProperty("probe.sign", required(
            active_config, "trial.probe-sign"))
        props.setProperty("probe.label", required(
            active_config, "trial.probe-label"))
    elif measurement_mode.startswith("response-candidate-"):
        props.setProperty("candidate.analysis-id", required(
            active_config, "trial.analysis-id"))
        props.setProperty("candidate.source-analysis.sha256", required(
            active_config, "trial.source-analysis.sha256"))
        props.setProperty("candidate.predicted-residual.x.mm", required(
            active_config, "trial.candidate-predicted-residual.x.mm"))
        props.setProperty("candidate.predicted-residual.y.mm", required(
            active_config, "trial.candidate-predicted-residual.y.mm"))
    write_properties(props, stamped, latest, mirrors=mirrors)

    lines = []
    state = "failed"
    post_home_reset = not perform_home
    old_x_method = None
    old_y_method = None
    try:
        if perform_home:
            window.update("Running normal OpenPnP Home before regression...", 0)
            machine.home()
            if not machine.isHomed():
                raise RuntimeError("OpenPnP Home did not establish a homed state.")
            post_home = status_values(CoreXYScriptBootstrap.status())
            if post_home.get("homing-bypass") != "false" or \
                    post_home.get("A-state") != "0" or \
                    post_home.get("B-state") != "0":
                raise RuntimeError(
                    "CoreXY state was not reset after OpenPnP Home.")
            post_home_reset = True
            props.setProperty("integrity.post-home-state-reset", "true")
        target = float(required(policy, "target.radial-tolerance.mm"))
        preferred = float(required(
            policy, "home-reference.preferred-distance.mm"))
        minimum = float(required(policy, "home-reference.minimum-distance.mm"))
        speed = float(required(policy, "home-reference.speed-factor"))
        repeats = int(active_config.getProperty(
            "trial.probe-repeats",
            required(policy, "home-reference.repeats")))
        settle_ms = int(required(policy, "home-reference.settle.ms"))
        stationary_captures = int(required(
            policy, "home-reference.stationary-captures"))

        primary = head.getCalibrationPrimaryFiducialLocation().convertToUnits(
            LengthUnit.Millimeters)
        home = head.getHomingFiducialLocation().convertToUnits(
            LengthUnit.Millimeters)
        reference = Location(
            LengthUnit.Millimeters, home.getX(), home.getY(),
            primary.getZ(), home.getRotation())
        diameter = head.getCalibrationPrimaryFiducialDiameter()
        if abs(reference.getX()) < 0.000001 or \
                abs(reference.getY()) < 0.000001:
            raise ValueError("The visual Home fiducial is not configured.")
        if diameter.convertToUnits(LengthUnit.Millimeters).getValue() <= 0.0:
            raise ValueError("The Primary Fiducial feature diameter is invalid.")
        if not camera.isReachable(reference):
            raise ValueError("The configured visual Home fiducial is unreachable.")

        x_axis = camera.getAxisX()
        y_axis = camera.getAxisY()
        if not isinstance(x_axis, ReferenceControllerAxis) or \
                not isinstance(y_axis, ReferenceControllerAxis):
            raise ValueError("The camera X/Y axes are not controller axes.")
        old_x_method = x_axis.getBacklashCompensationMethod()
        old_y_method = y_axis.getBacklashCompensationMethod()
        x_axis.setBacklashCompensationMethod(BacklashCompensationMethod.None)
        y_axis.setBacklashCompensationMethod(BacklashCompensationMethod.None)

        completed = 0
        window.update("Centering the Home visual fiducial...", completed)
        MovableUtils.moveToLocationAtSafeZ(camera, reference, speed)
        camera.waitForCompletion(CompletionType.WaitForStillstand)
        vision = machine.getVisionSolutions()
        detected_reference = vision.centerInOnSubjectLocation(
            camera, camera, diameter, "CoreXY Home Retest Reference", False)
        detected_reference = detected_reference.convertToUnits(
            LengthUnit.Millimeters)
        completed += 1

        distance, x_start, y_start = select_reachable_distance(
            camera, detected_reference, preferred, minimum)
        set_float(props, "target.radial-tolerance.mm", target)
        set_float(props, "reference.configured.x.mm", reference.getX())
        set_float(props, "reference.configured.y.mm", reference.getY())
        set_float(props, "reference.detected.x.mm", detected_reference.getX())
        set_float(props, "reference.detected.y.mm", detected_reference.getY())
        set_float(props, "reference.z.mm", detected_reference.getZ())
        set_float(props, "test.distance.mm", distance)
        set_float(props, "test.speed-factor", speed)
        props.setProperty("test.repeats", str(repeats))
        props.setProperty("path.b-positive", "X- start, X+ return")
        props.setProperty("path.b-negative", "Y- start, Y+ return")

        noise_x = []
        noise_y = []
        for capture in range(stationary_captures):
            if stop_requested.get():
                raise RuntimeError("Home retest stop requested by the user.")
            detected = CoreXYVisualDiagnostics.detectLocation(
                machine, camera, detected_reference, diameter,
                "CoreXY Home Stationary #{0}".format(capture + 1))
            noise_x.append(detected.getX())
            noise_y.append(detected.getY())
            completed += 1
            window.update("Measuring Home stationary vision noise...", completed)
        noise_center_x = median(noise_x)
        noise_center_y = median(noise_y)
        stationary_noise = maximum_radial_scatter(
            noise_x, noise_y, noise_center_x, noise_center_y)

        correction_x = []
        correction_y = []
        b_positive_locations = []
        b_negative_locations = []
        for repeat in range(repeats):
            sequence = ([('positive', x_start), ('negative', y_start)]
                        if repeat % 2 == 0 else
                        [('negative', y_start), ('positive', x_start)])
            captured = {}
            for state_name, start in sequence:
                if stop_requested.get():
                    raise RuntimeError(
                        "Home retest stop requested by the user.")
                camera.moveTo(start, speed)
                camera.waitForCompletion(CompletionType.WaitForStillstand)
                camera.moveTo(detected_reference, speed)
                camera.waitForCompletion(CompletionType.WaitForStillstand)
                Thread.sleep(settle_ms)
                if state_name == 'positive':
                    label = "B+"
                    path_text = "X- start, X+ return"
                else:
                    label = "B-"
                    path_text = "Y- start, Y+ return"
                captured[state_name] = CoreXYVisualDiagnostics.detectLocation(
                    machine, camera, detected_reference, diameter,
                    "CoreXY Home {0} #{1}".format(label, repeat + 1))
                completed += 1
                window.update(
                    "Home {0} return {1}/{2}: {3}".format(
                        label, repeat + 1, repeats, path_text), completed)
            b_positive = captured['positive']
            b_negative = captured['negative']
            b_positive_locations.append(b_positive)
            b_negative_locations.append(b_negative)
            dx = b_positive.getX() - b_negative.getX()
            dy = b_positive.getY() - b_negative.getY()
            correction_x.append(dx)
            correction_y.append(dy)
            sample = "sample.{0}".format(repeat + 1)
            set_float(props, sample + ".b-positive.x.mm", b_positive.getX())
            set_float(props, sample + ".b-positive.y.mm", b_positive.getY())
            set_float(props, sample + ".b-negative.x.mm", b_negative.getX())
            set_float(props, sample + ".b-negative.y.mm", b_negative.getY())
            set_float(props, sample + ".difference.x.mm", dx)
            set_float(props, sample + ".difference.y.mm", dy)

        center_x = median(correction_x)
        center_y = median(correction_y)
        magnitude = math.hypot(center_x, center_y)
        scatter = maximum_radial_scatter(
            correction_x, correction_y, center_x, center_y)
        units_per_pixel = camera.getUnitsPerPixel().convertToUnits(
            LengthUnit.Millimeters)
        detection_floor = max(
            0.005, abs(units_per_pixel.getX()) * 0.25,
            abs(units_per_pixel.getY()) * 0.25)
        uncertainty = max(detection_floor, stationary_noise) * 3.0 + scatter
        upper_bound = magnitude + uncertainty

        if measurement_mode == "post-home-regression":
            classification = "REGRESSION_CAPTURED"
            state = "completed"
        elif measurement_mode.startswith("response-candidate-"):
            if upper_bound <= target:
                classification = "CANDIDATE_PASS_ROBUST"
            elif magnitude <= target:
                classification = "CANDIDATE_PASS_MARGINAL"
            else:
                classification = "CANDIDATE_FAIL_TARGET"
            state = "completed"
        elif measurement_mode.startswith("response-probe-"):
            classification = "PROBE_CAPTURED"
            state = "completed"
        elif measurement_mode == "uncompensated-baseline":
            classification = "BASELINE_CAPTURED"
            state = "completed"
        elif upper_bound <= target:
            classification = "PASS_ROBUST"
            state = "passed"
        elif magnitude <= target:
            classification = "PASS_MARGINAL"
            state = "warning"
        else:
            classification = "FAIL_TARGET"
            state = "failed-acceptance"

        set_float(props, "vision.detection-floor.mm", detection_floor)
        set_float(props, "vision.stationary-noise.mm", stationary_noise)
        set_float(props, "result.b-state-difference.x.mm", center_x)
        set_float(props, "result.b-state-difference.y.mm", center_y)
        set_float(props, "result.radial-residual.mm", magnitude)
        set_float(props, "result.scatter.mm", scatter)
        set_float(props, "result.uncertainty-guard.mm", uncertainty)
        set_float(props, "result.upper-bound.mm", upper_bound)
        props.setProperty("result.classification", classification)

        config_hash_after = sha256(formal_file)
        runtime_after = status_values(CoreXYScriptBootstrap.status())
        if measurement_mode == "post-home-regression":
            title = "OpenPnP CoreXY Primary Production Post-Home Regression"
            integrity = (
                config_hash_before == config_hash_after and
                runtime_after.get("enabled") == "true" and
                runtime_after.get("dry-run") == "false" and
                runtime_after.get("homing-bypass") == "false" and
                post_home_reset and
                same_path(runtime_after.get("config", ""), formal_file))
        elif measurement_mode == "uncompensated-baseline":
            integrity = (
                config_hash_before == config_hash_after and
                runtime_after.get("enabled") == "false" and
                runtime_after.get("dry-run") == "false" and
                same_path(runtime_after.get("config", ""), active_file) and
                active_config.getProperty("trial.kind", "") ==
                "home-reference-baseline" and
                active_config.getProperty("trial.persistence", "") ==
                "process-only" and
                active_config.getProperty(
                    "trial.source-production-config.sha256", "").lower() ==
                config_hash_before.lower())
        elif (measurement_mode.startswith("response-probe-") or
              measurement_mode.startswith("response-candidate-")):
            expected_kind = ("home-response-probe"
                             if measurement_mode.startswith("response-probe-")
                             else "home-response-candidate")
            integrity = (
                config_hash_before == config_hash_after and
                runtime_after.get("enabled") == "true" and
                runtime_after.get("dry-run") == "false" and
                runtime_after.get("mode") == "state-offset" and
                same_path(runtime_after.get("config", ""), active_file) and
                active_config.getProperty("trial.kind", "") ==
                expected_kind and
                active_config.getProperty("trial.persistence", "") ==
                "process-only" and
                active_config.getProperty(
                    "trial.source-production-config.sha256", "").lower() ==
                config_hash_before.lower())
        else:
            integrity = (config_hash_before == config_hash_after and
                         runtime_after.get("enabled") == "true" and
                         runtime_after.get("dry-run") == "false" and
                         same_path(runtime_after.get("config", ""), formal_file))
        props.setProperty("integrity.config-unchanged", str(
            config_hash_before == config_hash_after).lower())
        props.setProperty("integrity.runtime-formal-active", str(
            integrity).lower())
        if not integrity:
            classification = "FAIL_INTEGRITY"
            state = "failed"
            props.setProperty("result.classification", classification)

        peer = comparable_measurement(
            peer_latest, speed, distance,
            required(formal, "production.promoted-at"))
        comparison_lines = []
        if peer is not None and measurement_mode.startswith("response-candidate-"):
            baseline_x = float(required(
                peer, "result.b-state-difference.x.mm"))
            baseline_y = float(required(
                peer, "result.b-state-difference.y.mm"))
            baseline_magnitude = math.hypot(baseline_x, baseline_y)
            candidate_percent = required(
                active_config, "trial.candidate-percent")
            offset_x = float(required(
                active_config, "trial.candidate-offset.x.mm"))
            offset_y = float(required(
                active_config, "trial.candidate-offset.y.mm"))
            improvement = baseline_magnitude - magnitude
            improvement_percent = (0.0 if baseline_magnitude <= 0.0 else
                                   improvement * 100.0 / baseline_magnitude)
            props.setProperty("candidate.percent", candidate_percent)
            set_float(props, "candidate.offset.x.mm", offset_x)
            set_float(props, "candidate.offset.y.mm", offset_y)
            set_float(props, "comparison.baseline-residual.mm",
                      baseline_magnitude)
            set_float(props, "comparison.candidate-residual.mm", magnitude)
            set_float(props, "comparison.improvement.mm", improvement)
            set_float(props, "comparison.improvement.percent",
                      improvement_percent)
            props.setProperty("comparison.available", "true")
            props.setProperty("comparison.peer", peer_latest.getCanonicalPath())
            comparison_lines = [
                "Comparable uncompensated Home baseline found.",
                "Candidate {0}% B+ offset: ({1:+.6f}, {2:+.6f}) mm".format(
                    candidate_percent, offset_x, offset_y),
                "Baseline vector: ({0:+.6f}, {1:+.6f}) mm".format(
                    baseline_x, baseline_y),
                "Candidate vector: ({0:+.6f}, {1:+.6f}) mm".format(
                    center_x, center_y),
                "Uncompensated -> candidate: {0:.6f} -> {1:.6f} mm".format(
                    baseline_magnitude, magnitude),
                "Measured improvement: {0:+.6f} mm ({1:+.1f}%)".format(
                    improvement, improvement_percent),
            ]
        elif peer is not None and measurement_mode.startswith("response-probe-"):
            baseline_x = float(required(
                peer, "result.b-state-difference.x.mm"))
            baseline_y = float(required(
                peer, "result.b-state-difference.y.mm"))
            offset_x = float(required(
                active_config, "trial.probe-offset.x.mm"))
            offset_y = float(required(
                active_config, "trial.probe-offset.y.mm"))
            probe_axis = required(active_config, "trial.probe-axis").lower()
            probe_sign = required(active_config, "trial.probe-sign").lower()
            probe_label = required(active_config, "trial.probe-label").lower()
            component = offset_x if probe_axis == "x" else offset_y
            if abs(component) < 0.000001:
                raise ValueError("The active Home probe component is zero.")
            effect_x = baseline_x - center_x
            effect_y = baseline_y - center_y
            response_x = effect_x / component
            response_y = effect_y / component
            props.setProperty("probe.axis", probe_axis)
            props.setProperty("probe.sign", probe_sign)
            props.setProperty("probe.label", probe_label)
            set_float(props, "probe.offset.x.mm", offset_x)
            set_float(props, "probe.offset.y.mm", offset_y)
            set_float(props, "probe.effect.x.mm", effect_x)
            set_float(props, "probe.effect.y.mm", effect_y)
            set_float(props, "probe.response.x-per-mm", response_x)
            set_float(props, "probe.response.y-per-mm", response_y)
            props.setProperty("comparison.available", "true")
            props.setProperty("comparison.peer", peer_latest.getCanonicalPath())
            comparison_lines = [
                "Comparable uncompensated Home baseline found.",
                "Configured B-{0} probe: ({1:+.6f}, {2:+.6f}) mm".format(
                    probe_label.replace("-plus", "+").replace(
                        "-minus", "-").upper(), offset_x, offset_y),
                "Baseline vector: ({0:+.6f}, {1:+.6f}) mm".format(
                    baseline_x, baseline_y),
                "Probe vector: ({0:+.6f}, {1:+.6f}) mm".format(
                    center_x, center_y),
                "Observed removal: ({0:+.6f}, {1:+.6f}) mm".format(
                    effect_x, effect_y),
                "Response column: ({0:+.4f}, {1:+.4f}) mm/mm".format(
                    response_x, response_y),
            ]
        elif peer is not None and measurement_mode == "post-home-regression":
            peer_magnitude = float(required(
                peer, "result.radial-residual.mm"))
            drift = magnitude - peer_magnitude
            set_float(props, "comparison.reference-residual.mm",
                      peer_magnitude)
            set_float(props, "comparison.current-residual.mm", magnitude)
            set_float(props, "comparison.drift.mm", drift)
            props.setProperty("comparison.peer", peer_latest.getCanonicalPath())
            comparison_lines = [
                "Comparable accepted post-Home reference found.",
                "Reference -> current: {0:.6f} -> {1:.6f} mm".format(
                    peer_magnitude, magnitude),
                "Observed Home B drift: {0:+.6f} mm".format(drift),
            ]
        elif peer is not None:
            peer_magnitude = float(required(
                peer, "result.radial-residual.mm"))
            if measurement_mode == "uncompensated-baseline":
                baseline_magnitude = magnitude
                compensated_magnitude = peer_magnitude
                peer_label = "formal compensated"
            else:
                baseline_magnitude = peer_magnitude
                compensated_magnitude = magnitude
                peer_label = "uncompensated baseline"
            improvement = baseline_magnitude - compensated_magnitude
            improvement_percent = (0.0 if baseline_magnitude <= 0.0 else
                                   improvement * 100.0 / baseline_magnitude)
            set_float(props, "comparison.baseline-residual.mm",
                      baseline_magnitude)
            set_float(props, "comparison.compensated-residual.mm",
                      compensated_magnitude)
            set_float(props, "comparison.improvement.mm", improvement)
            set_float(props, "comparison.improvement.percent",
                      improvement_percent)
            props.setProperty("comparison.peer", peer_latest.getCanonicalPath())
            comparison_lines = [
                "Comparable {0} result found.".format(peer_label),
                "Uncompensated -> compensated: {0:.6f} -> {1:.6f} mm".format(
                    baseline_magnitude, compensated_magnitude),
                "Measured improvement: {0:+.6f} mm ({1:+.1f}%)".format(
                    improvement, improvement_percent),
            ]
        else:
            props.setProperty("comparison.available", "false")
            if measurement_mode == "post-home-regression":
                if integrity:
                    comparison_lines = [
                        "No accepted same-revision post-Home reference exists.",
                        "This integrity-passing result will become the reference.",
                    ]
                else:
                    comparison_lines = [
                        "No accepted same-revision post-Home reference exists.",
                        "This result failed integrity and will not become the reference.",
                    ]
            else:
                comparison_lines = [
                    "No same-revision, same-speed, same-distance peer result exists.",
                    "Run the other mode to obtain a direct before/after comparison.",
                ]
        if peer is not None:
            props.setProperty("comparison.available", "true")

        if measurement_mode == "post-home-regression":
            title = "OpenPnP CoreXY Primary Production Post-Home Regression"
        elif measurement_mode == "uncompensated-baseline":
            title = "OpenPnP CoreXY Home Uncompensated Baseline"
        elif measurement_mode.startswith("response-candidate-"):
            title = "OpenPnP CoreXY Home B-state Candidate Validation"
        elif measurement_mode.startswith("response-probe-"):
            title = "OpenPnP CoreXY Home B-state Response Probe"
        else:
            title = "OpenPnP CoreXY Home Formal Compensation Retest"
        lines = [
            title,
            "",
            "Result: " + classification,
            "Measurement mode: " + measurement_mode,
            "Reference role: validation only; not used for surface fitting",
            "Configured Home: X={0:.6f}, Y={1:.6f} mm".format(
                reference.getX(), reference.getY()),
            "Vision-centered Home: X={0:.6f}, Y={1:.6f} mm".format(
                detected_reference.getX(), detected_reference.getY()),
            "Test: speed {0:.0%}, inward distance {1:.1f} mm, repeats {2}".format(
                speed, distance, repeats),
            "",
            "B+ sample: X- start, then X+ return to Home (A+, B+)",
            "B- sample: Y- start, then Y+ return to Home (A+, B-)",
            "Both paths hold A+ in common, so their difference isolates B.",
            "A+/A- cannot be isolated at this maximum-X/maximum-Y corner.",
            "",
            "B state residual vector: ({0:+.6f}, {1:+.6f}) mm".format(
                center_x, center_y),
            "2D radial residual: {0:.6f} mm; user target {1:.6f} mm".format(
                magnitude, target),
            "Scatter: {0:.6f} mm; uncertainty guard: {1:.6f} mm; "
            "upper bound: {2:.6f} mm".format(scatter, uncertainty, upper_bound),
            "Production configuration integrity: " +
            ("PASS" if integrity else "FAIL"),
            "",
        ] + comparison_lines + [
            "",
            "No candidate, model, or production configuration was changed."
        ]
    except Exception as error:
        state = "cancelled" if stop_requested.get() else "failed"
        props.setProperty("error", str(error))
        lines = [
            "OpenPnP CoreXY Home B-state Measurement",
            "",
            "State: " + state,
            "Error: " + str(error),
            "",
            "No production configuration was changed."
        ]
    finally:
        if old_x_method is not None:
            try:
                x_axis.setBacklashCompensationMethod(old_x_method)
            except Exception:
                pass
        if old_y_method is not None:
            try:
                y_axis.setBacklashCompensationMethod(old_y_method)
            except Exception:
                pass
        props.setProperty("state", state)
        props.setProperty(
            "completed", SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()))
        write_properties(props, stamped, latest, mirrors=mirrors)
        if (measurement_mode == "post-home-regression" and
                state == "completed" and integrity and
                not peer_latest.isFile()):
            write_properties(
                props, peer_latest, peer_latest,
                comment="Accepted Primary post-Home regression reference")
        writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
        try:
            writer.write("\r\n".join(lines))
        finally:
            writer.close()
        try:
            machine.setEnabled(False)
        except Exception as disable_error:
            lines.extend(["", "Machine disable error: " + str(disable_error)])

    class ShowResult(Runnable):
        def run(inner_self):
            window.close()
            message_type = (JOptionPane.INFORMATION_MESSAGE
                            if state in ["passed", "completed"]
                            else JOptionPane.WARNING_MESSAGE)
            JOptionPane.showMessageDialog(
                None,
                "\n".join(lines) +
                "\n\nMachine disabled after the Home measurement." +
                "\n\nData: " + stamped.getAbsolutePath() +
                "\nReport: " + report.getAbsolutePath(),
                "CoreXY Home B-state Measurement", message_type)

    SwingUtilities.invokeLater(ShowResult())


try:
    if not formal_file.isFile() or not policy_file.isFile():
        raise IOError(
            "Formal CoreXY configuration and calibration policy are required.")
    policy = load_properties(policy_file)
    if required(policy, "home-reference.enabled").lower() != "true":
        raise ValueError("Home validation reference is disabled in the policy.")
    formal = load_properties(formal_file)
    status = status_values(CoreXYScriptBootstrap.status())
    active_file = File(status.get("config", ""))
    if not active_file.isFile():
        raise ValueError("The active CoreXY configuration cannot be read.")
    active_config = load_properties(active_file)
    machine = Configuration.get().getMachine()
    if not isinstance(machine, ReferenceMachine):
        raise ValueError("The configured machine is not a ReferenceMachine.")
    if not machine.isEnabled():
        raise ValueError("Enable the machine before Home regression or retest.")
    if not AUTO_HOME_REGRESSION and not machine.isHomed():
        raise ValueError("Home the machine before Home retest.")
    if status.get("homing-bypass") != "false":
        raise ValueError("The Home bypass window has not finished.")
    if formal.getProperty("production.persistence", "") != "persistent":
        raise ValueError("The formal configuration is not persistent production.")

    formal_mode = (
        status.get("enabled") == "true" and
        status.get("dry-run") == "false" and
        same_path(active_file, formal_file))
    baseline_mode = (
        status.get("enabled") == "false" and
        status.get("dry-run") == "false" and
        active_config.getProperty("trial.kind", "") ==
        "home-reference-baseline" and
        active_config.getProperty("trial.persistence", "") ==
        "process-only" and
        same_path(active_config.getProperty(
            "trial.source-production-config", ""), formal_file) and
        active_config.getProperty(
            "trial.source-production-config.sha256", "").lower() ==
        sha256(formal_file).lower())
    probe_axis = active_config.getProperty("trial.probe-axis", "").lower()
    probe_sign = active_config.getProperty("trial.probe-sign", "").lower()
    probe_label = active_config.getProperty("trial.probe-label", "").lower()
    probe_mode = (
        status.get("enabled") == "true" and
        status.get("dry-run") == "false" and
        status.get("mode") == "state-offset" and
        active_config.getProperty("trial.kind", "") ==
        "home-response-probe" and
        active_config.getProperty("trial.persistence", "") ==
        "process-only" and
        probe_axis in ["x", "y"] and
        probe_sign in ["positive", "negative"] and
        probe_label in ["x-plus", "x-minus", "y-plus", "y-minus"] and
        same_path(active_config.getProperty(
            "trial.source-production-config", ""), formal_file) and
        active_config.getProperty(
            "trial.source-production-config.sha256", "").lower() ==
        sha256(formal_file).lower())
    candidate_percent = active_config.getProperty(
        "trial.candidate-percent", "")
    candidate_mode = (
        status.get("enabled") == "true" and
        status.get("dry-run") == "false" and
        status.get("mode") == "state-offset" and
        active_config.getProperty("trial.kind", "") ==
        "home-response-candidate" and
        active_config.getProperty("trial.persistence", "") ==
        "process-only" and
        candidate_percent in ["50", "75", "100"] and
        same_path(active_config.getProperty(
            "trial.source-production-config", ""), formal_file) and
        active_config.getProperty(
            "trial.source-production-config.sha256", "").lower() ==
        sha256(formal_file).lower())
    if (not formal_mode and not baseline_mode and not probe_mode and
            not candidate_mode):
        raise ValueError(
            "Run either the persistent formal CoreXY configuration or the "
            "transient Home baseline/response-probe/candidate mode.")
    if AUTO_HOME_REGRESSION:
        if not formal_mode:
            raise ValueError(
                "Post-Home regression requires the persistent Primary-only "
                "formal configuration.")
        measurement_mode = "post-home-regression"
    elif baseline_mode:
        measurement_mode = "uncompensated-baseline"
    elif probe_mode:
        measurement_mode = "response-probe-" + probe_label
    elif candidate_mode:
        measurement_mode = "response-candidate-" + candidate_percent
    else:
        measurement_mode = "formal-compensated"

    head = machine.getDefaultHead()
    if not isinstance(head, ReferenceHead):
        raise ValueError("The default head is not a ReferenceHead.")
    camera = head.getDefaultCamera()
    if not isinstance(camera, ReferenceCamera):
        raise ValueError("The default camera is not a ReferenceCamera.")

    home = head.getHomingFiducialLocation().convertToUnits(
        LengthUnit.Millimeters)
    preferred = float(required(
        policy, "home-reference.preferred-distance.mm"))
    speed = float(required(policy, "home-reference.speed-factor"))
    target = float(required(policy, "target.radial-tolerance.mm"))
    if AUTO_HOME_REGRESSION:
        mode_text = (
            "PRIMARY-ONLY PRODUCTION; normal Home then validation-only "
            "Home regression")
        expected = "HOME AND VERIFY"
    elif baseline_mode:
        mode_text = "UNCOMPENSATED BASELINE; CoreXY correction is OFF"
        expected = "HOME BASELINE"
    elif probe_mode:
        probe_x = float(required(
            active_config, "trial.probe-offset.x.mm"))
        probe_y = float(required(
            active_config, "trial.probe-offset.y.mm"))
        mode_text = (
            "B-{0} RESPONSE PROBE; offset ({1:+.3f}, {2:+.3f}) mm".format(
                probe_label.replace("-plus", "+").replace(
                    "-minus", "-").upper(), probe_x, probe_y))
        expected = "HOME PROBE " + probe_label.replace(
            "-plus", "+").replace("-minus", "-").upper()
    elif candidate_mode:
        candidate_x = float(required(
            active_config, "trial.candidate-offset.x.mm"))
        candidate_y = float(required(
            active_config, "trial.candidate-offset.y.mm"))
        mode_text = (
            "HOME RESPONSE CANDIDATE {0}%; offset ({1:+.3f}, {2:+.3f}) mm".format(
                candidate_percent, candidate_x, candidate_y))
        expected = "HOME CANDIDATE " + candidate_percent
    else:
        mode_text = "FORMAL COMPENSATED; production correction is ON"
        expected = "HOME RETEST"
    confirmation = JOptionPane.showInputDialog(
        None,
        "This measurement moves the top camera around the Home visual "
        "fiducial.\n\n"
        "Mode: {0}\n"
        "Home: X={1:.3f}, Y={2:.3f} mm\n"
        "Preferred inward distance: {3:.1f} mm; speed: {4:.0%}\n"
        "2D radial target: {5:.3f} mm\n"
        "Only inward X-/Y- starts are used; reachability is checked.\n"
        "The machine will be disabled after the test.\n\n"
        "Clear the complete travel area and type {6} to continue.".format(
            mode_text, home.getX(), home.getY(), preferred, speed, target,
            expected),
        "CoreXY Home B-state Measurement", JOptionPane.WARNING_MESSAGE)
    if confirmation is None or str(confirmation).strip().upper() != expected:
        raise RuntimeError("Home measurement cancelled.")

    ensure_directory(diagnostics_dir)
    ensure_directory(reports_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    if AUTO_HOME_REGRESSION:
        file_stem = "primary-post-home-regression"
        latest_name = "latest-primary-post-home-regression.properties"
        peer_name = "accepted-primary-post-home-regression.properties"
    elif baseline_mode:
        file_stem = "home-reference-baseline"
        latest_name = "latest-home-reference-baseline.properties"
        peer_name = "latest-home-reference-retest.properties"
    elif probe_mode:
        file_stem = "home-response-probe-" + probe_label
        latest_name = (
            "latest-home-response-probe-" + probe_label + ".properties")
        peer_name = "latest-home-reference-baseline.properties"
    elif candidate_mode:
        file_stem = "home-response-candidate-" + candidate_percent
        latest_name = "latest-home-response-candidate.properties"
        peer_name = "latest-home-reference-baseline.properties"
    else:
        file_stem = "home-reference-retest"
        latest_name = "latest-home-reference-retest.properties"
        peer_name = "latest-home-reference-baseline.properties"
    stamped = File(
        diagnostics_dir, file_stem + "-" + stamp + ".properties")
    latest = File(diagnostics_dir, latest_name)
    mirrors = []
    if candidate_mode:
        mirrors.append(File(
            diagnostics_dir, "latest-home-response-candidate-" +
            candidate_percent + ".properties"))
    peer_latest = File(diagnostics_dir, peer_name)
    report = File(reports_dir, file_stem + "-" + stamp + ".txt")
    repeats = int(active_config.getProperty(
        "trial.probe-repeats", required(policy, "home-reference.repeats")))
    stationary = int(required(policy, "home-reference.stationary-captures"))
    total = 1 + stationary + repeats * 2
    stop_requested = AtomicBoolean(False)
    window = ProgressWindow(total, stop_requested)
    window.show()

    def run_machine_retest():
        run_retest(machine, head, camera, formal, policy, measurement_mode,
                   active_file, active_config, window, stop_requested,
                   stamped, latest, peer_latest, report, mirrors,
                   AUTO_HOME_REGRESSION)

    UiUtils.submitUiMachineTask(run_machine_retest)
except RuntimeError as error:
    if "cancel" not in str(error).lower():
        JOptionPane.showMessageDialog(
            None, str(error), "CoreXY Home B-state Measurement",
            JOptionPane.ERROR_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Home B-state Measurement Error",
        JOptionPane.ERROR_MESSAGE)
    raise
