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
sessions_dir = File(runtime_dir, "sessions")
current_session_file = File(sessions_dir, "current-session.properties")
FRESH_HOME_SCAN = bool(globals().get("COREXY_FRESH_HOME_SCAN", False))

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


def set_float(props, key, value):
    props.setProperty(key, "{0:.9f}".format(float(value)))


def parse_numbers(text, defaults):
    source = str(text).strip() if text is not None else ""
    if not source:
        return list(defaults)
    values = []
    for token in source.replace(";", ",").split(","):
        token = token.strip()
        if token:
            values.append(float(token))
    return sorted(set(values))


def median(values):
    ordered = sorted([float(value) for value in values])
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) * 0.5


def scatter(xs, ys, center_x, center_y):
    return max([math.hypot(xs[index] - center_x, ys[index] - center_y)
                for index in range(len(xs))])


def summarize(values):
    ordered = sorted([float(value) for value in values])
    rms = math.sqrt(sum([value * value for value in ordered]) / len(ordered))
    p95_index = max(0, int(math.ceil(len(ordered) * 0.95)) - 1)
    return rms, ordered[p95_index], ordered[-1]


def ensure_directory(path):
    if not path.isDirectory() and not path.mkdirs():
        raise IOError("Cannot create directory: " + path.getAbsolutePath())


def write_properties(props, stamped, latest):
    for output in [stamped, latest]:
        stream = FileOutputStream(output)
        try:
            props.store(stream, "OpenPnP CoreXY Home B surface scan")
        finally:
            stream.close()


class ProgressWindow(object):
    def __init__(self, total, stop_requested):
        self.total = total
        self.stop_requested = stop_requested
        self.dialog = JDialog(None, "CoreXY Home B Surface Scan", False)
        self.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE)
        self.label = JLabel("Preparing Home B surface scan...")
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
        panel.setPreferredSize(Dimension(680, 90))
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


def run_scan(machine, head, camera, formal, policy, active_file,
             active_config, speeds, distances, repeats, window,
             stop_requested, stamped, latest, report, validation_mode,
             baseline, fresh_role=None, session=None):
    props = Properties()
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    props.setProperty("schema.version", "1")
    props.setProperty(
        "source", "home-b-spatial-physical-validation" if validation_mode
        else "home-b-uncompensated-surface-scan")
    props.setProperty("state", "running")
    props.setProperty("created", stamp)
    props.setProperty(
        "measurement.mode", "dual-reference-home-b-physical-validation"
        if validation_mode else "uncompensated-home-b-surface")
    props.setProperty("reference.role", "validation-and-spatial-candidate")
    props.setProperty("reference.observable-state", "B")
    props.setProperty("reference.unobservable-state", "A")
    props.setProperty("source-policy", policy_file.getCanonicalPath())
    props.setProperty("source-production-config", formal_file.getCanonicalPath())
    formal_hash = sha256(formal_file)
    props.setProperty("source-production-config.sha256", formal_hash)
    props.setProperty("production.revision",
                      required(formal, "production.promoted-at"))
    props.setProperty("runtime-config", active_file.getCanonicalPath())
    if fresh_role:
        props.setProperty("session.id", required(session, "session.id"))
        props.setProperty("measurement.mode", fresh_role)
        props.setProperty("policy.sha256", required(session, "policy.sha256"))
        props.setProperty("compensation.applied",
                          "true" if fresh_role == "formal-home-b" else "false")
    if validation_mode:
        props.setProperty("source-baseline", baseline.getProperty(
            "source.file", "latest-home-b-surface-scan.properties"))
        props.setProperty("candidate.id", required(
            active_config, "trial.candidate-id"))
        props.setProperty("compensation.applied", "true")
    write_properties(props, stamped, latest)

    old_x_method = None
    old_y_method = None
    lines = []
    state = "failed"
    try:
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
        MovableUtils.moveToLocationAtSafeZ(camera, reference, max(speeds))
        camera.waitForCompletion(CompletionType.WaitForStillstand)
        detected_reference = machine.getVisionSolutions().centerInOnSubjectLocation(
            camera, camera, diameter, "CoreXY Home Surface Reference", False)
        detected_reference = detected_reference.convertToUnits(
            LengthUnit.Millimeters)
        completed += 1

        requested_distances = list(distances)
        reachable = []
        starts = {}
        for distance in distances:
            x_start = detected_reference.add(Location(
                LengthUnit.Millimeters, -distance, 0.0, 0.0, 0.0))
            y_start = detected_reference.add(Location(
                LengthUnit.Millimeters, 0.0, -distance, 0.0, 0.0))
            if camera.isReachable(x_start) and camera.isReachable(y_start):
                reachable.append(distance)
                starts[distance] = (x_start, y_start)
        distances = reachable
        if validation_mode and distances != requested_distances:
            raise ValueError(
                "The saved Home baseline grid is no longer fully reachable. "
                "No partial or reordered comparison is permitted.")
        if len(distances) < 3:
            raise ValueError(
                "Fewer than three common inward Home distances are reachable.")
        window.total = 1 + stationary_captures + \
            len(speeds) * len(distances) * repeats * 2

        set_float(props, "reference.configured.x.mm", reference.getX())
        set_float(props, "reference.configured.y.mm", reference.getY())
        set_float(props, "reference.detected.x.mm", detected_reference.getX())
        set_float(props, "reference.detected.y.mm", detected_reference.getY())
        set_float(props, "reference.z.mm", detected_reference.getZ())
        props.setProperty("grid.speed.count", str(len(speeds)))
        props.setProperty("grid.distance.count", str(len(distances)))
        props.setProperty("grid.repeats", str(repeats))
        for index, speed in enumerate(speeds, 1):
            set_float(props, "grid.speed.{0}".format(index), speed)
        for index, distance in enumerate(distances, 1):
            set_float(props, "grid.distance.{0}.mm".format(index), distance)

        noise_x = []
        noise_y = []
        for capture in range(stationary_captures):
            if stop_requested.get():
                raise RuntimeError("Home surface scan stopped by the user.")
            detected = CoreXYVisualDiagnostics.detectLocation(
                machine, camera, detected_reference, diameter,
                "CoreXY Home Surface Noise #{0}".format(capture + 1))
            noise_x.append(detected.getX())
            noise_y.append(detected.getY())
            completed += 1
            window.update("Measuring stationary Home vision noise...", completed)
        noise_center_x = median(noise_x)
        noise_center_y = median(noise_y)
        stationary_noise = scatter(
            noise_x, noise_y, noise_center_x, noise_center_y)
        units_per_pixel = camera.getUnitsPerPixel().convertToUnits(
            LengthUnit.Millimeters)
        detection_floor = max(
            0.005, abs(units_per_pixel.getX()) * 0.25,
            abs(units_per_pixel.getY()) * 0.25)
        set_float(props, "vision.detection-floor.mm", detection_floor)
        set_float(props, "vision.stationary-noise.mm", stationary_noise)

        cell_index = 0
        for speed_index, speed in enumerate(speeds, 1):
            for distance_index, distance in enumerate(distances, 1):
                cell_index += 1
                x_start, y_start = starts[distance]
                values_x = []
                values_y = []
                prefix = "cell.{0}".format(cell_index)
                set_float(props, prefix + ".speed-factor", speed)
                set_float(props, prefix + ".distance.mm", distance)
                props.setProperty(prefix + ".sample.count", str(repeats))
                for repeat in range(repeats):
                    order = ([('positive', x_start), ('negative', y_start)]
                             if repeat % 2 == 0 else
                             [('negative', y_start), ('positive', x_start)])
                    captured = {}
                    for state_name, start in order:
                        if stop_requested.get():
                            raise RuntimeError(
                                "Home surface scan stopped by the user.")
                        camera.moveTo(start, speed)
                        camera.waitForCompletion(CompletionType.WaitForStillstand)
                        camera.moveTo(detected_reference, speed)
                        camera.waitForCompletion(CompletionType.WaitForStillstand)
                        Thread.sleep(settle_ms)
                        label = "B+" if state_name == 'positive' else "B-"
                        captured[state_name] = CoreXYVisualDiagnostics.detectLocation(
                            machine, camera, detected_reference, diameter,
                            "CoreXY Home Surface {0} s{1} d{2} r{3}".format(
                                label, speed_index, distance_index, repeat + 1))
                        completed += 1
                        window.update(
                            "Home B grid {0}/{1}, speed {2:.0%}, distance "
                            "{3:.1f} mm, repeat {4}/{5}, {6}".format(
                                cell_index, len(speeds) * len(distances),
                                speed, distance, repeat + 1, repeats, label),
                            completed)
                    positive = captured['positive']
                    negative = captured['negative']
                    dx = positive.getX() - negative.getX()
                    dy = positive.getY() - negative.getY()
                    values_x.append(dx)
                    values_y.append(dy)
                    sample = prefix + ".sample.{0}".format(repeat + 1)
                    set_float(props, sample + ".difference.x.mm", dx)
                    set_float(props, sample + ".difference.y.mm", dy)
                center_x = median(values_x)
                center_y = median(values_y)
                cell_scatter = scatter(
                    values_x, values_y, center_x, center_y)
                set_float(props, prefix + ".residual.x.mm", center_x)
                set_float(props, prefix + ".residual.y.mm", center_y)
                set_float(props, prefix + ".residual.mm",
                          math.hypot(center_x, center_y))
                set_float(props, prefix + ".scatter.mm", cell_scatter)
        props.setProperty("cell.count", str(cell_index))

        hash_after = sha256(formal_file)
        runtime_after = status_values(CoreXYScriptBootstrap.status())
        expected_kind = ("dual-reference-home-b-physical"
                         if validation_mode else "home-reference-baseline")
        if fresh_role == "formal-home-b":
            integrity = (
                hash_after == formal_hash and
                runtime_after.get("enabled") == "true" and
                runtime_after.get("dry-run") == "false" and
                same_path(runtime_after.get("config", ""), formal_file))
        else:
            integrity = (
                hash_after == formal_hash and
                runtime_after.get("enabled") ==
                ("true" if validation_mode else "false") and
                runtime_after.get("dry-run") == "false" and
                same_path(runtime_after.get("config", ""), active_file) and
                active_config.getProperty("trial.kind", "") == expected_kind and
                active_config.getProperty("trial.persistence", "") ==
                "process-only")
        props.setProperty("integrity.config-unchanged",
                          str(hash_after == formal_hash).lower())
        props.setProperty("integrity.runtime-mode-active",
                          str(integrity).lower())
        if not integrity:
            raise RuntimeError("Home surface scan integrity check failed.")
        state = "completed"
        title = ("OpenPnP CoreXY Fresh Home B Scan" if fresh_role else
                 ("OpenPnP CoreXY Home B Spatial Physical Validation"
                  if validation_mode else
                  "OpenPnP CoreXY Home B Uncompensated Surface Scan"))
        lines = [
            title, "",
            "Result: COMPLETED",
            "Reference role: second spatial anchor; B observable, A unavailable",
            "Home: X={0:.6f}, Y={1:.6f} mm".format(
                detected_reference.getX(), detected_reference.getY()),
            "Speeds: " + ", ".join(
                ["{0:.0%}".format(value) for value in speeds]),
            "Reachable inward distances: " + ", ".join(
                ["{0:.1f}".format(value) for value in distances]) + " mm",
            "Cells: {0}; repeats per cell: {1}".format(cell_index, repeats),
            "Vision floor: {0:.6f} mm; stationary noise: {1:.6f} mm".format(
                detection_floor, stationary_noise), "",
        ]
        for index in range(1, cell_index + 1):
            prefix = "cell.{0}".format(index)
            lines.append(
                "{0:02d}: speed {1:.0%}, distance {2:.1f}; B vector "
                "({3:+.6f}, {4:+.6f}), radial {5:.6f}, scatter "
                "{6:.6f} mm".format(
                    index, float(required(props, prefix + ".speed-factor")),
                    float(required(props, prefix + ".distance.mm")),
                    float(required(props, prefix + ".residual.x.mm")),
                    float(required(props, prefix + ".residual.y.mm")),
                    float(required(props, prefix + ".residual.mm")),
                    float(required(props, prefix + ".scatter.mm"))))
        if validation_mode:
            before_values = []
            after_values = []
            changes = []
            target = float(required(policy, "target.radial-tolerance.mm"))
            for index in range(1, cell_index + 1):
                prefix = "cell.{0}".format(index)
                baseline_speed = float(required(
                    baseline, prefix + ".speed-factor"))
                baseline_distance = float(required(
                    baseline, prefix + ".distance.mm"))
                current_speed = float(required(
                    props, prefix + ".speed-factor"))
                current_distance = float(required(
                    props, prefix + ".distance.mm"))
                if abs(baseline_speed - current_speed) > 0.000001 or \
                        abs(baseline_distance - current_distance) > 0.000001:
                    raise ValueError(
                        "Home baseline and validation cell grids do not match.")
                before = float(required(
                    baseline, prefix + ".residual.mm"))
                after = float(required(props, prefix + ".residual.mm"))
                before_values.append(before)
                after_values.append(after)
                changes.append((after - before, index, before, after))
                set_float(props, prefix + ".baseline.mm", before)
                set_float(props, prefix + ".change.mm", after - before)
            before_metrics = summarize(before_values)
            after_metrics = summarize(after_values)
            worst = max(changes)
            local_limit = max(0.015, detection_floor * 3.0,
                              stationary_noise * 2.0)
            before_target = len([value for value in before_values
                                 if value <= target])
            after_target = len([value for value in after_values
                                if value <= target])
            checks = [
                ("Home B RMS improves by at least 5%",
                 after_metrics[0] <= before_metrics[0] * 0.95),
                ("Home B P95 does not worsen",
                 after_metrics[1] <= before_metrics[1] + detection_floor),
                ("Home B maximum does not worsen",
                 after_metrics[2] <= before_metrics[2] + detection_floor),
                ("No same-cell worsening exceeds the vision guard",
                 worst[0] <= local_limit),
                ("The number of cells meeting the user target does not fall",
                 after_target >= before_target),
            ]
            passed = all([value for label, value in checks])
            state = "passed" if passed else "failed-acceptance"
            props.setProperty("validation.passed", str(passed).lower())
            props.setProperty("acceptance.policy", "home-spatial-v1")
            set_float(props, "baseline.rms.mm", before_metrics[0])
            set_float(props, "baseline.p95.mm", before_metrics[1])
            set_float(props, "baseline.maximum.mm", before_metrics[2])
            set_float(props, "physical.rms.mm", after_metrics[0])
            set_float(props, "physical.p95.mm", after_metrics[1])
            set_float(props, "physical.maximum.mm", after_metrics[2])
            set_float(props, "physical.improvement.percent",
                      100.0 * (before_metrics[0] - after_metrics[0]) /
                      before_metrics[0])
            set_float(props, "acceptance.local-worsening-limit.mm", local_limit)
            set_float(props, "physical.worst-cell-worsening.mm", worst[0])
            props.setProperty("physical.worst-cell.index", str(worst[1]))
            props.setProperty("target.baseline-cell-count", str(before_target))
            props.setProperty("target.physical-cell-count", str(after_target))
            for index, check in enumerate(checks, 1):
                props.setProperty(
                    "acceptance.check.{0}.description".format(index), check[0])
                props.setProperty(
                    "acceptance.check.{0}.passed".format(index),
                    str(check[1]).lower())
            lines += ["", "Before/after summary:",
                      "RMS: {0:.6f} -> {1:.6f} mm ({2:+.1f}% improvement)".format(
                          before_metrics[0], after_metrics[0],
                          100.0 * (before_metrics[0] - after_metrics[0]) /
                          before_metrics[0]),
                      "P95: {0:.6f} -> {1:.6f} mm".format(
                          before_metrics[1], after_metrics[1]),
                      "Maximum: {0:.6f} -> {1:.6f} mm".format(
                          before_metrics[2], after_metrics[2]),
                      "Cells meeting {0:.3f} mm target: {1} -> {2} / {3}".format(
                          target, before_target, after_target, cell_index),
                      "Worst same-cell worsening: {0:+.6f} mm; limit {1:.6f} mm".format(
                          worst[0], local_limit), "", "Acceptance checks:"]
            for label, value in checks:
                lines.append(("PASS  " if value else "FAIL  ") + label)
            lines += ["", "Home spatial validation passed: " +
                      str(passed).lower()]
        lines += ["", "No model, candidate, or formal configuration was changed."]
    except Exception as error:
        state = "cancelled" if stop_requested.get() else "failed"
        props.setProperty("error", str(error))
        lines = [
            "OpenPnP CoreXY Home B Surface Measurement", "",
            "State: " + state, "Error: " + str(error), "",
            "No formal configuration was changed.",
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
        write_properties(props, stamped, latest)
        writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
        try:
            writer.write("\r\n".join(lines))
        finally:
            writer.close()
        try:
            machine.setEnabled(False)
        except Exception as disable_error:
            lines += ["", "Machine disable error: " + str(disable_error)]

    class ShowResult(Runnable):
        def run(inner_self):
            window.close()
            JOptionPane.showMessageDialog(
                None, "\n".join(lines) +
                "\n\nMachine disabled after the Home surface scan." +
                "\n\nData: " + stamped.getAbsolutePath() +
                "\nReport: " + report.getAbsolutePath(),
                ("CoreXY Home B Spatial Validation" if validation_mode else
                 "CoreXY Home B Surface Scan"),
                JOptionPane.INFORMATION_MESSAGE
                if state in ["completed", "passed"] else
                JOptionPane.WARNING_MESSAGE)

    SwingUtilities.invokeLater(ShowResult())


try:
    for path in [formal_file, policy_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    formal = load_properties(formal_file)
    policy = load_properties(policy_file)
    status = status_values(CoreXYScriptBootstrap.status())
    active_file = File(status.get("config", ""))
    if not active_file.isFile():
        raise ValueError("The active CoreXY configuration cannot be read.")
    active_config = load_properties(active_file)
    session = load_properties(current_session_file) \
        if FRESH_HOME_SCAN and current_session_file.isFile() else None
    if FRESH_HOME_SCAN and session is None:
        raise RuntimeError("Start a fresh same-time session first.")
    if FRESH_HOME_SCAN and required(session, "formal.sha256").lower() != \
            sha256(formal_file).lower():
        raise RuntimeError("The formal configuration changed after session start.")
    if FRESH_HOME_SCAN and required(session, "policy.sha256").lower() != \
            sha256(policy_file).lower():
        raise RuntimeError("The calibration policy changed after session start.")
    machine = Configuration.get().getMachine()
    if not isinstance(machine, ReferenceMachine):
        raise ValueError("The configured machine is not a ReferenceMachine.")
    if not machine.isEnabled() or not machine.isHomed():
        raise ValueError("Enable and Home the machine before the scan.")
    if status.get("homing-bypass") != "false":
        raise ValueError("The Home bypass window has not finished.")
    baseline_mode = (status.get("enabled") == "false" and
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
    validation_mode = (status.get("enabled") == "true" and
            status.get("dry-run") == "false" and
            status.get("mode") ==
            "distance-speed-position-state-offset" and
            active_config.getProperty("trial.kind", "") ==
            "dual-reference-home-b-physical" and
            active_config.getProperty("trial.persistence", "") ==
            "process-only" and
            active_config.getProperty(
                "trial.source-production-config.sha256", "").lower() ==
            sha256(formal_file).lower())
    formal_session_mode = (FRESH_HOME_SCAN and
            status.get("enabled") == "true" and
            status.get("dry-run") == "false" and
            status.get("mode") == "distance-speed-state-offset" and
            same_path(active_file, formal_file))
    fresh_role = ("formal-home-b" if formal_session_mode else
                  ("uncompensated-home-b" if FRESH_HOME_SCAN and
                   baseline_mode else None))
    if not baseline_mode and not validation_mode and not formal_session_mode:
        raise ValueError(
            "Load either the Home uncompensated mode or the dual-reference "
            "physical trial first.")

    baseline = Properties()
    baseline_path = File(
        diagnostics_dir, "latest-home-b-surface-scan.properties")
    if validation_mode:
        candidate_path = File(required(
            active_config, "trial.source-candidate"))
        if candidate_path.isFile() and sha256(candidate_path).lower() == \
                required(active_config,
                         "trial.source-candidate.sha256").lower():
            candidate = load_properties(candidate_path)
            candidate_baseline = candidate.getProperty("source-home-scan")
            if candidate_baseline is not None and \
                    str(candidate_baseline).strip():
                baseline_path = File(str(candidate_baseline).strip())
                if sha256(baseline_path).lower() != required(
                        candidate, "source-home-scan.sha256").lower():
                    raise ValueError("The candidate Home baseline hash changed.")
        if not baseline_path.isFile():
            raise IOError("The uncompensated Home B surface baseline is missing.")
        baseline = load_properties(baseline_path)
        baseline.setProperty("source.file", baseline_path.getCanonicalPath())
        if required(baseline, "state") != "completed" or \
                required(baseline, "measurement.mode") not in \
                ["uncompensated-home-b-surface", "uncompensated-home-b"] or \
                required(baseline,
                         "source-production-config.sha256").lower() != \
                sha256(formal_file).lower():
            raise ValueError(
                "The uncompensated Home baseline is incomplete or stale.")
        speeds = [float(required(
            baseline, "grid.speed.{0}".format(index)))
            for index in range(1, int(required(
                baseline, "grid.speed.count")) + 1)]
        distances = [float(required(
            baseline, "grid.distance.{0}.mm".format(index)))
            for index in range(1, int(required(
                baseline, "grid.distance.count")) + 1)]
        repeats = int(required(baseline, "grid.repeats"))
    else:
        speeds = parse_numbers(policy.getProperty("home-surface.speeds"),
                               [0.40, 0.70, 0.90])
        distances = parse_numbers(
            policy.getProperty("home-surface.distances.mm"),
            [15.0, 30.0, 60.0, 120.0, 180.0, 270.0])
        repeats = int(policy.getProperty("home-surface.repeats", "5"))
    if len(speeds) < 2 or any([value <= 0.0 or value > 1.0
                               for value in speeds]):
        raise ValueError("Home surface scan requires at least two speeds in (0,1].")
    minimum_distance = float(required(
        policy, "home-reference.minimum-distance.mm"))
    distances = [value for value in distances if value >= minimum_distance]
    if len(distances) < 3 or repeats < 3:
        raise ValueError(
            "Home surface scan requires at least three distances and three repeats.")

    head = machine.getDefaultHead()
    if not isinstance(head, ReferenceHead):
        raise ValueError("The default head is not a ReferenceHead.")
    camera = head.getDefaultCamera()
    if not isinstance(camera, ReferenceCamera):
        raise ValueError("The default camera is not a ReferenceCamera.")
    home = head.getHomingFiducialLocation().convertToUnits(
        LengthUnit.Millimeters)
    movement_count = len(speeds) * len(distances) * repeats * 2
    action_title = ("Fresh same-time Home B scan" if fresh_role else
                    ("Dual-reference compensated Home B validation"
                     if validation_mode else
                     "Uncompensated Home B distance/speed surface scan"))
    confirmation_text = ("FRESH HOME" if fresh_role else
                         ("HOME SPATIAL VALIDATE" if validation_mode
                          else "HOME SURFACE SCAN"))
    answer = JOptionPane.showInputDialog(
        None,
        action_title + "\n\n"
        "Home: X={0:.3f}, Y={1:.3f} mm\n"
        "Speeds: {2}\nDistances: {3} mm\n"
        "Repeats per cell: {4}; planned visual returns: {5}\n\n"
        "Only inward X-/Y- starts are used and every path is checked against "
        "OpenPnP reachability. Soft limits cannot detect fixtures.\n"
        "Clear the full inward travel area and keep emergency stop ready.\n\n"
        "The transient mode and formal configuration are hash-checked. "
        "No formal configuration will be changed.\n\n"
        "Type {6} to continue.".format(
            home.getX(), home.getY(),
            ", ".join(["{0:.0%}".format(value) for value in speeds]),
            ", ".join(["{0:.1f}".format(value) for value in distances]),
            repeats, movement_count, confirmation_text),
        "CoreXY Home B Surface Scan", JOptionPane.WARNING_MESSAGE)
    if answer is None or str(answer).strip().upper() != confirmation_text:
        raise RuntimeError("Home surface scan cancelled.")

    ensure_directory(diagnostics_dir)
    ensure_directory(reports_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    file_prefix = (fresh_role if fresh_role else
                   ("dual-reference-home-validation" if validation_mode
                    else "home-b-surface-scan"))
    if fresh_role:
        session_dir = File(required(session, "session.directory"))
        stamped = File(session_dir, file_prefix + ".properties")
        latest = stamped
        report = File(session_dir, file_prefix + ".txt")
    else:
        stamped = File(
            diagnostics_dir, file_prefix + "-" + stamp + ".properties")
        latest = File(
            diagnostics_dir, "latest-" + file_prefix + ".properties")
        report = File(reports_dir, file_prefix + "-" + stamp + ".txt")
    total = 1 + int(required(policy, "home-reference.stationary-captures")) + \
        movement_count
    stop_requested = AtomicBoolean(False)
    window = ProgressWindow(total, stop_requested)
    window.show()

    def task():
        run_scan(machine, head, camera, formal, policy, active_file,
                 active_config, speeds, distances, repeats, window,
                 stop_requested, stamped, latest, report, validation_mode,
                 baseline, fresh_role, session)

    UiUtils.submitUiMachineTask(task)
except RuntimeError as error:
    if "cancel" not in str(error).lower():
        JOptionPane.showMessageDialog(
            None, str(error), "CoreXY Home B Surface Scan",
            JOptionPane.ERROR_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Home B Surface Scan Error",
        JOptionPane.ERROR_MESSAGE)
    raise
