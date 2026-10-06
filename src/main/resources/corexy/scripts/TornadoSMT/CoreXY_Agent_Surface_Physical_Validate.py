from __future__ import unicode_literals

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


LEGACY_FIXED_VALIDATION = bool(globals().get(
    "COREXY_LEGACY_FIXED_VALIDATION", False))
TRADITIONAL_SEGMENTED_VALIDATION = bool(globals().get(
    "COREXY_TRADITIONAL_SEGMENTED_VALIDATION", False))
SPEEDS = [0.40, 0.70, 0.90]
DISTANCES = [15.0, 28.0, 34.0, 60.0, 180.0, 270.0]
REPEATS = 3
SETTLE_MS = 500
STATIONARY_CAPTURES = 7

scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
formal_file = File(runtime_dir, "config/corexy.properties")
policy_file = File(runtime_dir, "config/calibration-policy.properties")
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
            props.store(stream, "OpenPnP CoreXY physical surface validation")
        finally:
            stream.close()


def summarize(values):
    ordered = sorted([float(value) for value in values])
    if not ordered:
        raise ValueError("Cannot summarize an empty measurement set.")
    rms = math.sqrt(sum([value * value for value in ordered]) / len(ordered))
    p95_index = max(0, int(math.ceil(len(ordered) * 0.95)) - 1)
    return {
        "rms": rms,
        "p95": ordered[p95_index],
        "maximum": ordered[-1]
    }


def store_metrics(props, prefix, metrics):
    set_float(props, prefix + ".rms.mm", metrics["rms"])
    set_float(props, prefix + ".p95.mm", metrics["p95"])
    set_float(props, prefix + ".maximum.mm", metrics["maximum"])


def load_metrics(props, prefix):
    return {
        "rms": float(required(props, prefix + ".rms.mm")),
        "p95": float(required(props, prefix + ".p95.mm")),
        "maximum": float(required(props, prefix + ".maximum.mm"))
    }


def validation_targets(trial, spatial_mode):
    if not spatial_mode:
        return (
            float(required(trial, "target.radial-tolerance.mm")),
            float(required(trial, "target.maximum-residual-guard.mm")))

    candidate_file = File(required(trial, "trial.source-candidate"))
    if not candidate_file.isFile():
        raise IOError("Dual-reference source candidate was not found: " +
                      candidate_file.getAbsolutePath())
    if sha256(candidate_file).lower() != required(
            trial, "trial.source-candidate.sha256").lower():
        raise ValueError("Dual-reference source candidate hash changed.")
    candidate = load_properties(candidate_file)
    target = float(required(candidate, "target.radial-tolerance.mm"))
    maximum_text = candidate.getProperty("target.maximum-residual-guard.mm")
    if maximum_text is not None and str(maximum_text).strip():
        maximum = float(str(maximum_text).strip())
    else:
        if not policy_file.isFile():
            raise IOError("Calibration policy was not found: " +
                          policy_file.getAbsolutePath())
        policy = load_properties(policy_file)
        measurable_margin = float(required(
            policy, "target.effective-minimum.mm"))
        maximum = target + measurable_margin
    return target, maximum


def improvement(before, after):
    if before <= 0.0:
        return 0.0
    return 100.0 * (before - after) / before


def metrics_line(label, baseline, physical):
    return (
        "{0}: RMS {1:.6f} -> {2:.6f} mm ({3:+.1f}% improvement); "
        "P95 {4:.6f} -> {5:.6f}; maximum {6:.6f} -> {7:.6f} mm"
    ).format(
        label, baseline["rms"], physical["rms"],
        improvement(baseline["rms"], physical["rms"]),
        baseline["p95"], physical["p95"],
        baseline["maximum"], physical["maximum"])


def baseline_point_magnitude(props, stage_index, direction, distance_index,
                             expected_speed, expected_distance):
    stage_prefix = "stage.{0}".format(stage_index + 1)
    speed = float(required(props, stage_prefix + ".speed-factor"))
    if abs(speed - expected_speed) > 0.000001:
        raise ValueError("Baseline speed grid does not match this validation.")
    prefix = stage_prefix + "." + direction.lower() + ".distance.{0}".format(
        distance_index + 1)
    distance = float(required(props, prefix + ".virtual-distance.mm"))
    if abs(distance - expected_distance) > 0.000001:
        raise ValueError("Baseline distance grid does not match this validation.")
    return float(required(props, prefix + ".magnitude.mm"))


class ProgressWindow(object):
    def __init__(self, total_steps, stop_requested):
        self.total_steps = total_steps
        self.stop_requested = stop_requested
        self.dialog = JDialog(None, "CoreXY Physical Validation", False)
        self.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE)
        self.label = JLabel("Preparing compensated visual validation...")
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

    def update(self, speed_index, speed, stage, completed, per_speed_total):
        overall = speed_index * per_speed_total + completed
        percent = int(round(100.0 * overall / max(1, self.total_steps)))
        parts = str(stage).split("|")
        labels = {
            "reference": "Centering the Primary Fiducial",
            "vision-noise": "Measuring stationary vision noise",
            "distance-a": "Measuring compensated A residuals",
            "distance-b": "Measuring compensated B residuals",
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
            raise RuntimeError("Physical validation stop requested by the user.")


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


def start_validation(status, trial_file, trial, baseline_file, baseline):
    ensure_directory(diagnostics_dir)
    ensure_directory(reports_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    spatial_mode = trial.getProperty("trial.kind", "") == \
        "dual-reference-home-b-physical"
    fixed_mode = (LEGACY_FIXED_VALIDATION and
                  trial.getProperty("trial.kind", "") ==
                  "legacy-fixed-vector-physical")
    segmented_mode = (TRADITIONAL_SEGMENTED_VALIDATION and
                      trial.getProperty("trial.kind", "") ==
                      "traditional-segmented-physical")
    file_prefix = ("dual-reference-primary-validation" if spatial_mode else
                   "legacy-fixed-physical-validation" if fixed_mode else
                   "traditional-segmented-physical-validation" if segmented_mode else
                   "auto-calibration-physical-validation")
    stamped = File(
        diagnostics_dir, file_prefix + "-" + stamp + ".properties")
    latest = File(diagnostics_dir, "latest-" + file_prefix + ".properties")
    gain = float(required(trial, "model.gain"))
    target_tolerance, target_maximum_guard = validation_targets(
        trial, spatial_mode)
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty(
        "source", "dual-reference-primary-regression-validation"
        if spatial_mode else "external-legacy-fixed-physical-validation"
        if fixed_mode else
        "external-traditional-segmented-physical-validation"
        if segmented_mode else
        "external-auto-calibration-physical-surface-validation")
    props.setProperty("state", "running")
    props.setProperty("created", stamp)
    props.setProperty("source-trial", trial_file.getCanonicalPath())
    props.setProperty("source-baseline", baseline_file.getCanonicalPath())
    if spatial_mode:
        props.setProperty("candidate.id", required(
            trial, "trial.candidate-id"))
        props.setProperty("source-production-config", formal_file.getCanonicalPath())
        props.setProperty("source-production-config.sha256", sha256(formal_file))
    props.setProperty("validation.role",
                      "primary-regression" if spatial_mode else
                      "legacy-fixed-candidate" if fixed_mode
                      else "traditional-segmented-candidate" if segmented_mode
                      else "production-candidate")
    props.setProperty("model.kind", required(trial, "model.kind"))
    set_float(props, "model.gain", gain)
    set_float(props, "target.radial-tolerance.mm", target_tolerance)
    set_float(props, "target.maximum-residual-guard.mm",
              target_maximum_guard)
    props.setProperty("compensation.applied", "true")
    props.setProperty("compensation.persistence", "process-only")
    props.setProperty("stage.count", str(len(SPEEDS)))
    write_properties(props, stamped, latest)

    per_speed_total = 2 + STATIONARY_CAPTURES + len(DISTANCES) * REPEATS * 4
    total_steps = per_speed_total * len(SPEEDS)
    stop_requested = AtomicBoolean(False)
    window = ProgressWindow(total_steps, stop_requested)
    window.show()

    def run_validation():
        state = "failed"
        error_text = ""
        lines = []
        try:
            all_values = []
            a_values = []
            b_values = []
            point_changes = []
            floors = []
            for speed_index, speed in enumerate(SPEEDS):
                listener = CombinedProgressListener(
                    window, stop_requested, speed_index, speed)
                result = CoreXYVisualDiagnostics.measureCompensatedDistanceScan(
                    array(DISTANCES, 'd'), REPEATS, speed, SETTLE_MS,
                    STATIONARY_CAPTURES, listener)
                store_stage(props, speed_index, speed, result)
                floors.append(result.getVisionDetectionFloorMm())
                for direction, values, target in [
                        ("A", list(result.getAResults()), a_values),
                        ("B", list(result.getBResults()), b_values)]:
                    for distance_index, value in enumerate(values):
                        magnitude = value.getMagnitudeMm()
                        target.append(magnitude)
                        all_values.append(magnitude)
                        original = baseline_point_magnitude(
                            baseline, speed_index, direction, distance_index,
                            speed, DISTANCES[distance_index])
                        point_changes.append({
                            "direction": direction,
                            "speed": speed,
                            "distance": DISTANCES[distance_index],
                            "baseline": original,
                            "physical": magnitude,
                            "worsening": magnitude - original
                        })
                props.setProperty(
                    "completed-stage-count", str(speed_index + 1))
                write_properties(props, stamped, latest)

            if spatial_mode:
                baseline_all = load_metrics(baseline, "physical.all")
                baseline_a = load_metrics(baseline, "physical.a")
                baseline_b = load_metrics(baseline, "physical.b")
            else:
                baseline_all = load_metrics(baseline, "validation.baseline")
                baseline_a = load_metrics(baseline, "validation.a.baseline")
                baseline_b = load_metrics(baseline, "validation.b.baseline")
            physical_all = summarize(all_values)
            physical_a = summarize(a_values)
            physical_b = summarize(b_values)
            worst_point = max(
                point_changes, key=lambda value: value["worsening"])
            worst_worsening = worst_point["worsening"]
            local_limit = max(0.015, max(floors) * 3.0)
            if spatial_mode:
                checks = [
                    ("Primary all RMS remains within the vision guard",
                     physical_all["rms"] <= baseline_all["rms"] +
                     max(floors)),
                    ("Primary A RMS remains within the vision guard",
                     physical_a["rms"] <= baseline_a["rms"] + max(floors)),
                    ("Primary B RMS remains within the vision guard",
                     physical_b["rms"] <= baseline_b["rms"] + max(floors)),
                    ("Primary P95 does not materially worsen",
                     physical_all["p95"] <= baseline_all["p95"] +
                     local_limit),
                    ("Primary maximum does not materially worsen",
                     physical_all["maximum"] <= baseline_all["maximum"] +
                     local_limit),
                    ("No local point exceeds the vision-aware guardrail",
                     worst_worsening <= local_limit),
                ]
            else:
                checks = [
                    ("All RMS improves by at least 10%",
                     physical_all["rms"] <= baseline_all["rms"] * 0.90),
                    ("B RMS improves by at least 10%",
                     physical_b["rms"] <= baseline_b["rms"] * 0.90),
                    ("All P95 does not worsen",
                     physical_all["p95"] <= baseline_all["p95"]),
                    ("All maximum does not worsen",
                     physical_all["maximum"] <= baseline_all["maximum"]),
                    ("A remains within its noise guardrail",
                     physical_a["rms"] <= max(
                         baseline_a["rms"] * 1.35,
                         baseline_a["rms"] + 0.010)),
                    ("No local point exceeds the vision-aware worsening guardrail",
                     worst_worsening <= local_limit),
                    ("All P95 meets the user 2D radial target",
                     physical_all["p95"] <= target_tolerance),
                    ("All maximum remains inside the target vision guard",
                     physical_all["maximum"] <= target_maximum_guard)
                ]
            passed = all([value for label, value in checks])
            state = "passed" if passed else "failed-acceptance"
            props.setProperty("validation.passed", str(passed).lower())
            store_metrics(props, "baseline.all", baseline_all)
            store_metrics(props, "baseline.a", baseline_a)
            store_metrics(props, "baseline.b", baseline_b)
            store_metrics(props, "physical.all", physical_all)
            store_metrics(props, "physical.a", physical_a)
            store_metrics(props, "physical.b", physical_b)
            set_float(props, "physical.maximum-worsening.mm", worst_worsening)
            set_float(props, "acceptance.local-worsening-limit.mm", local_limit)
            props.setProperty(
                "acceptance.policy", "primary-regression-v1"
                if spatial_mode else "vision-aware-v2")
            props.setProperty(
                "physical.maximum-worsening.direction",
                worst_point["direction"])
            set_float(props, "physical.maximum-worsening.speed-factor",
                      worst_point["speed"])
            set_float(props, "physical.maximum-worsening.distance.mm",
                      worst_point["distance"])
            set_float(props, "physical.maximum-worsening.baseline.mm",
                      worst_point["baseline"])
            set_float(props, "physical.maximum-worsening.result.mm",
                      worst_point["physical"])
            for index, check in enumerate(checks):
                props.setProperty(
                    "acceptance.check.{0}.passed".format(index + 1),
                    str(check[1]).lower())
                props.setProperty(
                    "acceptance.check.{0}.description".format(index + 1),
                    check[0])

            lines = [
                ("OpenPnP CoreXY Dual-reference Primary Regression"
                 if spatial_mode else
                 "OpenPnP CoreXY Legacy Fixed-vector Validation"
                 if fixed_mode else
                 "OpenPnP CoreXY Traditional Segmented Validation"
                 if segmented_mode else
                 "OpenPnP CoreXY Physical Surface Validation"),
                "",
                "State: " + state,
                "REAL compensation was active during every measurement.",
                "Model gain: {0:.0%}; persistence: current process only".format(
                    gain),
                ("Reference: validated formal 25% physical measurement"
                 if spatial_mode else
                 "Reference: uncompensated held-out measurement"),
                "User 2D radial target: {0:.6f} mm; maximum guard: "
                "{1:.6f} mm".format(
                    target_tolerance, target_maximum_guard),
                "Speeds: " + ", ".join(
                    ["{0:.0%}".format(value) for value in SPEEDS]),
                "Distances: " + ", ".join(
                    ["{0:.1f}".format(value) for value in DISTANCES]) + " mm",
                "",
                metrics_line("All", baseline_all, physical_all),
                metrics_line("A", baseline_a, physical_a),
                metrics_line("B", baseline_b, physical_b),
                "Worst same-cell worsening: {0:.6f} mm; limit {1:.6f} mm".format(
                    worst_worsening, local_limit),
                "Worst cell: {0}, speed {1:.0%}, distance {2:.1f} mm; "
                "{3:.6f} -> {4:.6f} mm".format(
                    worst_point["direction"], worst_point["speed"],
                    worst_point["distance"], worst_point["baseline"],
                    worst_point["physical"]),
                "",
                "Acceptance checks:"
            ]
            for label, value in checks:
                lines.append(("PASS  " if value else "FAIL  ") + label)
            lines.extend([
                "",
                "Physical validation passed: " + str(passed).lower(),
                "This result does not write or enable the formal configuration."
            ])
            if fixed_mode:
                lines.extend([
                    "", "中文摘要：",
                    "实机固定向量补偿已在全部测量中启用。",
                    "总RMS: {0:.6f} -> {1:.6f} mm；P95: {2:.6f} -> {3:.6f} mm；最大值: {4:.6f} -> {5:.6f} mm".format(
                        baseline_all["rms"], physical_all["rms"],
                        baseline_all["p95"], physical_all["p95"],
                        baseline_all["maximum"], physical_all["maximum"]),
                    "物理验收结果：" + ("通过" if passed else "未达到全部系统目标"),
                    "本次结果不会写入或启用正式配置。"
                ])
        except Exception as error:
            error_text = str(error)
            state = "cancelled" if stop_requested.get() else "failed"
            props.setProperty("error.message", error_text)
            lines = [
            ("OpenPnP CoreXY Dual-reference Primary Regression"
             if spatial_mode else
             "OpenPnP CoreXY Legacy Fixed-vector Validation"
             if fixed_mode else
             "OpenPnP CoreXY Traditional Segmented Validation"
             if segmented_mode else
             "OpenPnP CoreXY Physical Surface Validation"),
                "",
                "State: " + state,
                "Error: " + error_text,
                "",
                "No formal configuration was changed."
            ]
        finally:
            props.setProperty("state", state)
            props.setProperty(
                "completed", SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()))
            if error_text:
                props.setProperty("error", error_text)
            write_properties(props, stamped, latest)
            try:
                Configuration.get().getMachine().setEnabled(False)
            except Exception as disable_error:
                lines.extend(["", "Machine disable error: " + str(disable_error)])

        report = File(
            reports_dir,
            file_prefix + "-" + stamp + ".txt")
        writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
        try:
            writer.write("\r\n".join(lines))
        finally:
            writer.close()

        class ShowResult(Runnable):
            def run(inner_self):
                window.close()
                message = "\n".join(lines) + \
                    "\n\nMachine disabled after validation." + \
                    "\n\nData: " + stamped.getAbsolutePath() + \
                    "\nReport: " + report.getAbsolutePath()
                message_type = (JOptionPane.INFORMATION_MESSAGE
                                if state == "passed"
                                else JOptionPane.WARNING_MESSAGE)
                JOptionPane.showMessageDialog(
                    None, message,
                    ("CoreXY Dual-reference Primary Regression"
                     if spatial_mode else
                     "CoreXY Legacy Fixed-vector Validation"
                     if fixed_mode else
                     "CoreXY Traditional Segmented Validation"
                     if segmented_mode else "CoreXY Physical Validation"),
                    message_type)

        SwingUtilities.invokeLater(ShowResult())

    UiUtils.submitUiMachineTask(run_validation)


def prepare_validation():
    global SPEEDS, DISTANCES, REPEATS
    status = status_values(CoreXYScriptBootstrap.status())
    if status.get("configured") != "true":
        raise RuntimeError("CoreXY Agent is not configured.")
    mode = status.get("mode")
    expected_modes = (["state-offset"] if LEGACY_FIXED_VALIDATION else
                      ["distance-speed-state-offset",
                       "distance-speed-position-state-offset"])
    if mode not in expected_modes:
        raise RuntimeError("The expected transient compensation profile is not loaded.")
    if status.get("enabled") != "true" or status.get("dry-run") != "false":
        raise RuntimeError(
            "Load the recommended REAL physical trial before validation.")
    if status.get("homed") != "true" or status.get("homing-bypass") != "false":
        raise RuntimeError("Enable and Home the machine before validation.")
    if status.get("A-state") != "0" or status.get("B-state") != "0":
        raise RuntimeError("Home again so the initial A/B states are both zero.")
    config_path = status.get("config")
    if config_path is None or not str(config_path).strip():
        raise ValueError("Agent status does not contain the active config path.")
    trial_file = File(str(config_path).strip())
    if not trial_file.isFile():
        raise IOError("Transient trial file was not found: " +
                      trial_file.getAbsolutePath())
    trial = load_properties(trial_file)
    fixed_mode = (LEGACY_FIXED_VALIDATION and mode == "state-offset" and
                  trial.getProperty("trial.kind", "") ==
                  "legacy-fixed-vector-physical")
    if LEGACY_FIXED_VALIDATION and not fixed_mode:
        raise ValueError("The active state-offset profile is not a legacy trial.")
    segmented_mode = (TRADITIONAL_SEGMENTED_VALIDATION and
                      mode == "distance-speed-state-offset" and
                      trial.getProperty("trial.kind", "") ==
                      "traditional-segmented-physical")
    if TRADITIONAL_SEGMENTED_VALIDATION and not segmented_mode:
        raise ValueError("The active profile is not a traditional segmented trial.")
    spatial_mode = (mode == "distance-speed-position-state-offset" and
                    trial.getProperty("trial.kind", "") ==
                    "dual-reference-home-b-physical")
    if mode == "distance-speed-position-state-offset" and not spatial_mode:
        raise ValueError("The active spatial config is not an approved trial.")
    gain = float(required(trial, "model.gain"))
    if gain <= 0.0 or gain > 1.0:
        raise ValueError("The physical trial gain is outside (0, 1].")
    maximum_offset = float(required(trial, "safety.max-offset.mm"))
    if fixed_mode:
        if not policy_file.isFile():
            raise IOError("Calibration policy was not found.")
        policy = load_properties(policy_file)
        fixed_maximum = float(required(
            policy, "target.absolute-maximum.mm"))
        if maximum_offset > fixed_maximum + 0.000000001:
            raise ValueError(
                "Fixed-vector trial exceeds the policy absolute maximum.")
    elif maximum_offset > 0.075000001:
        raise ValueError("Physical trial maximum offset exceeds 0.075 mm.")
    if spatial_mode:
        if not formal_file.isFile():
            raise IOError("The formal CoreXY configuration is missing.")
        formal = load_properties(formal_file)
        candidate_file = File(required(trial, "trial.source-candidate"))
        candidate = load_properties(candidate_file)
        fresh_primary = candidate.getProperty("source-session-formal-primary")
        baseline_file = (File(str(fresh_primary).strip())
                         if fresh_primary is not None and
                         str(fresh_primary).strip() else
                         File(required(
                             formal, "production.source-physical-validation")))
        if fresh_primary is not None and str(fresh_primary).strip() and \
                sha256(baseline_file).lower() != required(
                    candidate,
                    "source-session-formal-primary.sha256").lower():
            raise ValueError("The candidate Primary baseline hash changed.")
    else:
        baseline_file = File(required(trial, "trial.source-validation"))
    if not baseline_file.isFile():
        raise IOError("Held-out baseline was not found: " +
                      baseline_file.getAbsolutePath())
    baseline = load_properties(baseline_file)
    if fixed_mode and baseline.getProperty("measurement.mode", "") == \
            "uncompensated-primary":
        stage_count = int(required(baseline, "stage.count"))
        SPEEDS = [float(required(
            baseline, "stage.{0}.speed-factor".format(index + 1)))
                  for index in range(stage_count)]
        distance_count = int(required(
            baseline, "stage.1.a.distance.count"))
        DISTANCES = [float(required(
            baseline, "stage.1.a.distance.{0}.virtual-distance.mm".format(
                index + 1))) for index in range(distance_count)]
        REPEATS = int(required(
            baseline, "stage.1.a.distance.1.sample.count"))
    if spatial_mode:
        fresh_baseline = baseline.getProperty("measurement.mode", "") == \
            "formal-primary"
        if fresh_baseline:
            if required(baseline, "state") != "completed" or \
                    required(baseline, "compensation.applied") != "true":
                raise ValueError("The fresh formal Primary reference is invalid.")
            for group in ["all", "a", "b"]:
                for metric in ["rms", "p95", "maximum"]:
                    baseline.setProperty(
                        "physical." + group + "." + metric + ".mm",
                        required(baseline,
                                 "current." + group + "." + metric + ".mm"))
        elif required(baseline, "compensation.applied") != "true" or \
                required(baseline, "state") not in \
                ["passed", "failed-acceptance"]:
            raise ValueError("The formal physical reference is invalid.")
    else:
        fresh_uncompensated = (
            baseline.getProperty("state", "") == "completed" and
            baseline.getProperty("measurement.mode", "") ==
            "uncompensated-primary" and
            baseline.getProperty("compensation.applied", "") == "false")
        if segmented_mode or (fixed_mode and fresh_uncompensated):
            if required(baseline, "state") != "completed" or \
                    required(baseline, "measurement.mode") != "uncompensated-primary" or \
                    required(baseline, "compensation.applied") != "false":
                raise ValueError("The fresh uncompensated Primary baseline is invalid.")
            for group in ["all", "a", "b"]:
                for metric in ["rms", "p95", "maximum"]:
                    target_prefix = ("validation.baseline" if group == "all"
                                     else "validation." + group + ".baseline")
                    baseline.setProperty(
                        target_prefix + "." + metric + ".mm",
                        required(baseline, "current." + group + "." + metric + ".mm"))
        elif required(baseline, "state") != "passed" or \
                required(baseline, "validation.passed") != "true":
            raise ValueError("The uncompensated held-out baseline did not pass.")
        if not fixed_mode and not segmented_mode and abs(float(required(
                baseline, "candidate-gain")) - gain) > 0.000001:
            raise ValueError("Trial gain and held-out baseline gain do not match.")

    machine = Configuration.get().getMachine()
    if not machine.isEnabled() or not machine.isHomed():
        raise RuntimeError("Enable and Home the machine before validation.")
    head = machine.getDefaultHead()
    primary = head.getCalibrationPrimaryFiducialLocation()
    half = max(DISTANCES) * 0.5
    message = (
        ("REAL dual-reference Primary regression test\n\n"
         if spatial_mode else
         "REAL legacy fixed-vector visual acceptance test\n\n"
         if fixed_mode else
         "REAL traditional segmented A/B visual acceptance test\n\n"
         if segmented_mode else
         "REAL compensated visual acceptance test\n\n") +
        "Gain: {0:.0%}; maximum offset: {1:.3f} mm\n"
        "Speeds: {2}\nDistances: {3} mm\n"
        "Grid cells: {4}; visual return measurements: {5}\n"
        "XY envelope: X {6:.1f} to {7:.1f}, Y {8:.1f} to {9:.1f} mm\n\n"
        "The same held-out grid will be measured with compensation active.\n"
        "The result will be compared with the saved {11}.\n"
        "No formal configuration will be written or enabled.\n"
        "The machine will be disabled automatically when the test ends.\n\n"
        "Soft limits cannot detect fixtures or loose objects.\n"
        "Clear the complete envelope and keep emergency stop ready.\n\n"
        "Type ACCEPT {10:.0f} to continue."
    ).format(
        gain, maximum_offset,
        ", ".join(["{0:.0%}".format(value) for value in SPEEDS]),
        ", ".join(["{0:.1f}".format(value) for value in DISTANCES]),
        len(SPEEDS) * len(DISTANCES),
        len(SPEEDS) * len(DISTANCES) * REPEATS * 4,
        primary.getX() - half, primary.getX() + half,
        primary.getY() - half, primary.getY() + half,
        gain * 100.0,
        ("validated formal physical reference" if spatial_mode
         else "uncompensated baseline"))
    confirmation = JOptionPane.showInputDialog(None, message, "")
    expected = "ACCEPT {0:.0f}".format(gain * 100.0)
    if confirmation is None or \
            str(confirmation).strip().upper() != expected:
        return
    start_validation(
        status, trial_file, trial, baseline_file, baseline)


try:
    prepare_validation()
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Physical Validation Error",
        JOptionPane.ERROR_MESSAGE)
    raise
