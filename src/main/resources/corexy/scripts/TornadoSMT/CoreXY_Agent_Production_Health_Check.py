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
from org.openpnp.model import Configuration, LengthUnit
from org.openpnp.util import UiUtils


SETTLE_MS = 500
STATIONARY_CAPTURES = 7
AUTOMATIC_CONFIRMATION_REPEATS = 7

scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
diagnostics_dir = File(runtime_dir, "diagnostics")
reports_dir = File(runtime_dir, "reports")
sessions_dir = File(runtime_dir, "sessions")
current_session_file = File(sessions_dir, "current-session.properties")
FRESH_PRIMARY_SCAN = bool(globals().get("COREXY_FRESH_PRIMARY_SCAN", False))

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
            props.store(stream, "OpenPnP CoreXY production health check")
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


def metric_reduction_percent(before, after):
    if before <= 0.000000001:
        return 0.0
    return (before - after) * 100.0 / before


def measurement_worst_cell(props):
    worst = (0.0, "?", 0.0, 0.0)
    stage_count = int(props.getProperty("stage.count", "0"))
    for stage_index in range(stage_count):
        stage = "stage.{0}".format(stage_index + 1)
        speed = float(props.getProperty(stage + ".speed-factor", "0"))
        for direction in ["a", "b"]:
            count = int(props.getProperty(
                stage + "." + direction + ".distance.count", "0"))
            for distance_index in range(count):
                prefix = (stage + "." + direction + ".distance.{0}".format(
                    distance_index + 1))
                magnitude = float(props.getProperty(
                    prefix + ".magnitude.mm", "0"))
                distance = float(props.getProperty(
                    prefix + ".virtual-distance.mm", "0"))
                if magnitude > worst[0]:
                    worst = (magnitude, direction.upper(), speed, distance)
    return worst


def absolute_return_stats(props, primary_x, primary_y):
    points = []
    stage_count = int(props.getProperty("stage.count", "0"))
    for stage_index in range(stage_count):
        stage = "stage.{0}".format(stage_index + 1)
        for direction in ["a", "b"]:
            distance_count = int(props.getProperty(
                stage + "." + direction + ".distance.count", "0"))
            for distance_index in range(distance_count):
                prefix = (stage + "." + direction + ".distance.{0}".format(
                    distance_index + 1))
                sample_count = int(props.getProperty(
                    prefix + ".sample.count", "0"))
                for sample_index in range(sample_count):
                    sample = prefix + ".sample.{0}".format(sample_index + 1)
                    for suffix in ["positive", "negative"]:
                        x_text = props.getProperty(
                            sample + "." + suffix + ".x.mm")
                        y_text = props.getProperty(
                            sample + "." + suffix + ".y.mm")
                        if x_text is not None and y_text is not None:
                            points.append((float(x_text) - primary_x,
                                           float(y_text) - primary_y))
    if not points:
        return None
    center_x = sum([point[0] for point in points]) / len(points)
    center_y = sum([point[1] for point in points]) / len(points)
    maximum = max([math.hypot(point[0], point[1]) for point in points])
    return (len(points), center_x, center_y, maximum)


def bias_text(x_value, y_value):
    horizontal = "X+" if x_value > 0.002 else (
        "X-" if x_value < -0.002 else "X centered")
    vertical = "Y+" if y_value > 0.002 else (
        "Y-" if y_value < -0.002 else "Y centered")
    return horizontal + ", " + vertical


def health_conclusion_lines(reference, current, reference_all, reference_a,
                            reference_b, current_all, current_a, current_b,
                            health_result):
    if reference_b["rms"] > reference_a["rms"] * 1.25:
        dominant = "B"
    elif reference_a["rms"] > reference_b["rms"] * 1.25:
        dominant = "A"
    else:
        dominant = "A/B"
    worst = measurement_worst_cell(reference)
    distance_class = (u"长行程 / long travel" if worst[3] >= 60.0 else
                      u"短行程或阈值附近 / short or threshold-region travel")
    overall_change = metric_reduction_percent(
        reference_all["rms"], current_all["rms"])
    a_change = metric_reduction_percent(reference_a["rms"], current_a["rms"])
    b_change = metric_reduction_percent(reference_b["rms"], current_b["rms"])
    primary = Configuration.get().getMachine().getDefaultHead().\
        getCalibrationPrimaryFiducialLocation().convertToUnits(
            LengthUnit.Millimeters)
    reference_absolute = absolute_return_stats(
        reference, primary.getX(), primary.getY())
    current_absolute = absolute_return_stats(
        current, primary.getX(), primary.getY())
    lines = [
        u"自动解读 / Automatic interpretation:",
        u"主要检测结论 / Main finding: 未补偿方向状态误差主要来自 {0}；"
        u"最坏单元 {1}、{2:.0f}%、{3:.1f} mm（{4}，{5:.6f} mm）。 / "
        u"Uncompensated direction-state error is dominated by {0}; worst cell "
        u"{1}, {2:.0f}%, {3:.1f} mm ({4}, {5:.6f} mm).".format(
            dominant, worst[1], worst[2] * 100.0, worst[3],
            distance_class, worst[0]),
        u"补偿效果 / Compensation result: 总体 RMS {0:.6f} -> {1:.6f} mm，"
        u"降低 {2:.1f}%；A {3:.1f}%，B {4:.1f}%。 / Overall RMS reduction "
        u"{2:.1f}%; A {3:.1f}%, B {4:.1f}%.".format(
            reference_all["rms"], current_all["rms"], overall_change,
            a_change, b_change)
    ]
    absolute_inside = False
    if current_absolute is not None:
        absolute_inside = current_absolute[3] <= 0.050
        lines.append(
            u"绝对落点 / Absolute returns: 中心 ({0:+.6f}, {1:+.6f}) mm，"
            u"偏向 {2}；最远点 {3:.6f} mm，{4} 0.05 mm参考圈。 / Center "
            u"bias {2}; farthest return {3:.6f} mm, {5} the 0.05 mm circle.".format(
                current_absolute[1], current_absolute[2],
                bias_text(current_absolute[1], current_absolute[2]),
                current_absolute[3], u"进入" if absolute_inside else u"未进入",
                "inside" if absolute_inside else "outside"))
    if health_result == "PASS" and not absolute_inside:
        conclusion = (
            u"方向状态误差已经受控，但仍有独立的共同绝对偏移；应检查 Primary "
            u"基准漂移或位置相关误差，不应继续用固定 A/B 向量追逐0.05 mm圈。 / "
            u"Direction-state error is controlled, but an independent common absolute "
            u"offset remains; inspect Primary/reference drift or position-dependent error.")
    elif health_result == "PASS":
        conclusion = (u"方向状态健康和0.05 mm绝对落点参考均满足。 / Direction-state "
                      u"health and the 0.05 mm absolute reference are both satisfied.")
    elif health_result == "WARNING":
        conclusion = (u"仍有可见方向状态变化；检查最坏单元和机械重复性后重新健康检查。 / "
                      u"A visible direction-state change remains; inspect the worst cell "
                      u"and mechanical repeatability, then repeat the check.")
    else:
        conclusion = (u"方向状态误差超过警告范围；生产使用前应停用补偿并检查机械结构。 / "
                      u"Direction-state error exceeds the warning range; stop compensation "
                      u"and inspect mechanics before production use.")
    lines.append(u"综合结论 / Overall ({0}): {1}".format(
        health_result, conclusion))
    return lines


def change_percent(reference, current):
    if reference <= 0.0:
        return 0.0
    return 100.0 * (current - reference) / reference


def metrics_line(label, reference, current):
    return (
        u"{0}: RMS {1:.6f} -> {2:.6f} mm（变化/change {3:+.1f}%）；"
        u"P95 {4:.6f} -> {5:.6f}；最大值/max {6:.6f} -> {7:.6f} mm"
    ).format(
        label, reference["rms"], current["rms"],
        change_percent(reference["rms"], current["rms"]),
        reference["p95"], current["p95"],
        reference["maximum"], current["maximum"])


def reference_point_magnitude(props, stage_index, direction, distance_index,
                              expected_speed, expected_distance):
    stage_prefix = "stage.{0}".format(stage_index + 1)
    speed = float(required(props, stage_prefix + ".speed-factor"))
    if abs(speed - expected_speed) > 0.000001:
        raise ValueError("Reference speed grid does not match this health check.")
    prefix = stage_prefix + "." + direction.lower() + ".distance.{0}".format(
        distance_index + 1)
    distance = float(required(props, prefix + ".virtual-distance.mm"))
    if abs(distance - expected_distance) > 0.000001:
        raise ValueError("Reference distance grid does not match this health check.")
    return float(required(props, prefix + ".magnitude.mm"))


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


def load_reference_grid(reference):
    stage_count = int(required(reference, "stage.count"))
    if stage_count <= 0:
        raise ValueError("The production reference has no speed stages.")
    speeds = []
    distances = None
    repeats = None
    for stage_index in range(stage_count):
        stage_prefix = "stage.{0}".format(stage_index + 1)
        speeds.append(float(required(
            reference, stage_prefix + ".speed-factor")))
        count = int(required(
            reference, stage_prefix + ".a.distance.count"))
        if count <= 0:
            raise ValueError("The production reference has no distances.")
        stage_distances = []
        for distance_index in range(count):
            point_prefix = stage_prefix + ".a.distance.{0}".format(
                distance_index + 1)
            stage_distances.append(float(required(
                reference, point_prefix + ".virtual-distance.mm")))
            point_repeats = int(required(
                reference, point_prefix + ".sample.count"))
            if repeats is None:
                repeats = point_repeats
            elif repeats != point_repeats:
                raise ValueError(
                    "Reference sample counts are not consistent.")
        if distances is None:
            distances = stage_distances
        elif distances != stage_distances:
            raise ValueError(
                "Reference distance grids are not consistent.")
        if int(required(reference,
                        stage_prefix + ".b.distance.count")) != count:
            raise ValueError("Reference A/B distance grids do not match.")
    return speeds, distances, repeats


def reference_vision_floor(reference):
    floors = []
    stage_count = int(required(reference, "stage.count"))
    for stage_index in range(stage_count):
        prefix = "stage.{0}".format(stage_index + 1)
        floors.append(float(required(
            reference, prefix + ".vision-detection-floor.mm")))
        floors.append(float(required(
            reference, prefix + ".stationary-noise.mm")))
    return max(floors)


def top_camera_pixel_size_mm(machine, primary):
    camera = machine.getDefaultHead().getDefaultCamera()
    units_per_pixel = camera.getUnitsPerPixel(
        primary.getLengthZ()).convertToUnits(LengthUnit.Millimeters)
    pixel_x = abs(units_per_pixel.getX())
    pixel_y = abs(units_per_pixel.getY())
    raw = max(pixel_x, pixel_y)
    rounded = round(raw, 3)
    if pixel_x <= 0.0 or pixel_y <= 0.0 or rounded <= 0.0:
        raise ValueError(
            "The top camera units-per-pixel calibration is not valid.")
    return pixel_x, pixel_y, raw, rounded


class ProgressWindow(object):
    def __init__(self, total_steps, stop_requested, language):
        self.total_steps = total_steps
        self.stop_requested = stop_requested
        self.language = language
        self.dialog = JDialog(
            None, u"CoreXY Production Health Check" if language == "en" else
            u"CoreXY 健康检查", False)
        self.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE)
        self.label = JLabel(
            u"Preparing CoreXY measurement..." if language == "en" else
            u"正在准备 CoreXY 测量...")
        self.bar = JProgressBar(0, 100)
        self.bar.setStringPainted(True)
        self.stop_button = JButton(
            u"Request Stop" if language == "en" else u"请求停止")

        class StopAction(ActionListener):
            def actionPerformed(inner_self, event):
                stop_requested.set(True)
                self.stop_button.setEnabled(False)
                self.label.setText(
                    u"Stop requested; waiting for the current return..."
                    if language == "en" else
                    u"已请求停止，正在等待当前回归动作完成...")

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
        labels = ({
            "reference": u"Centering the Primary Fiducial",
            "vision-noise": u"Measuring stationary vision noise",
            "distance-a": u"Checking A direction-state residuals",
            "distance-b": u"Checking B direction-state residuals",
            "return-reference": u"Returning to the Primary Fiducial",
            "complete": u"Speed stage complete",
            "failed": u"Measurement failed"
        } if self.language == "en" else {
            "reference": u"正在对中主要标定点",
            "vision-noise": u"正在测量静止视觉噪声",
            "distance-a": u"正在检查 A 方向状态残差",
            "distance-b": u"正在检查 B 方向状态残差",
            "return-reference": u"正在返回主要标定点",
            "complete": u"当前速度阶段完成",
            "failed": u"测量失败"
        })
        label = labels.get(parts[0], str(stage))
        if len(parts) >= 4 and parts[0].startswith("distance-"):
            label += ((u" at {0} mm ({1}/{2})" if self.language == "en" else
                       u"，距离 {0} mm（{1}/{2}）").format(
                           parts[3], parts[1], parts[2]))
        display = ((u"Speed {0:.0%}: {1}  {2}/{3}"
                    if self.language == "en" else
                    u"速度 {0:.0%}：{1}  {2}/{3}").format(
                        speed, label, overall, self.total_steps))

        class UpdateUi(Runnable):
            def run(inner_self):
                self.label.setText(display)
                self.bar.setValue(percent)

        SwingUtilities.invokeLater(UpdateUi())

    def begin_confirmation(self, total):
        self.confirmation_base = self.total_steps
        self.total_steps += total

    def update_confirmation(self, direction, speed, distance, stage,
                            completed, total):
        overall = self.confirmation_base + completed
        percent = int(round(100.0 * overall / max(1, self.total_steps)))
        parts = str(stage).split("|")
        labels = ({
            "reference": u"Centering the Primary Fiducial",
            "vision-noise": u"Measuring confirmation vision noise",
            "distance-a": u"Confirming the A companion cell",
            "distance-b": u"Confirming the B target cell",
            "return-reference": u"Returning to the Primary Fiducial",
            "complete": u"Automatic confirmation complete",
            "failed": u"Automatic confirmation failed"
        } if self.language == "en" else {
            "reference": u"正在对中主要标定点",
            "vision-noise": u"正在测量复测视觉噪声",
            "distance-a": u"正在复测 A 伴随单元",
            "distance-b": u"正在复测 B 目标单元",
            "return-reference": u"正在返回主要标定点",
            "complete": u"自动复测完成",
            "failed": u"自动复测失败"
        })
        label = labels.get(parts[0], str(stage))
        display = ((u"Automatic confirmation: {0}, {1:.0%}, {2:.1f} mm: "
                    u"{3}  {4}/{5}" if self.language == "en" else
                    u"自动复测：{0}，{1:.0%}，{2:.1f} mm："
                    u"{3}  {4}/{5}")).format(
                       direction, speed, distance, label, overall,
                       self.total_steps)

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
            raise RuntimeError("Production health check stop requested by the user.")


class AutomaticConfirmationProgressListener(
        CoreXYVisualDiagnostics.ProgressListener):
    def __init__(self, window, stop_requested, direction, speed, distance):
        self.window = window
        self.stop_requested = stop_requested
        self.direction = direction
        self.speed = speed
        self.distance = distance

    def onProgress(self, stage, completed, total):
        self.window.update_confirmation(
            self.direction, self.speed, self.distance, stage, completed, total)
        if self.stop_requested.get():
            raise RuntimeError("Production health check stop requested by the user.")


def automatic_confirmation(window, stop_requested, props, config_file, config,
                           config_hash_before, worst_point, pixel_size_mm):
    direction = worst_point["direction"]
    speed = worst_point["speed"]
    distance = worst_point["distance"]
    total = 2 + STATIONARY_CAPTURES + AUTOMATIC_CONFIRMATION_REPEATS * 4
    window.begin_confirmation(total)
    listener = AutomaticConfirmationProgressListener(
        window, stop_requested, direction, speed, distance)
    result = CoreXYVisualDiagnostics.measureCompensatedDistanceScan(
        array([distance], 'd'), AUTOMATIC_CONFIRMATION_REPEATS, speed,
        SETTLE_MS, STATIONARY_CAPTURES, listener)
    a_result = list(result.getAResults())[0]
    b_result = list(result.getBResults())[0]
    target = a_result if direction == "A" else b_result
    companion = b_result if direction == "A" else a_result
    reference_magnitude = worst_point["reference"]
    previous_magnitude = worst_point["current"]
    current_magnitude = target.getMagnitudeMm()
    degradation = current_magnitude - reference_magnitude
    repeat_change = abs(current_magnitude - previous_magnitude)
    observed_vision_noise = max(
        result.getVisionDetectionFloorMm(),
        result.getStationaryVisionNoiseMm())
    pass_guard = pixel_size_mm * 3.0
    warning_guard = pixel_size_mm * 6.0
    config_hash_after = sha256(config_file)
    runtime_after = status_values(CoreXYScriptBootstrap.status())
    integrity_ok = (
        config_hash_before == config_hash_after and
        runtime_after.get("enabled") == "true" and
        runtime_after.get("dry-run") == "false" and
        runtime_after.get("mode") == required(config, "mode") and
        same_path(runtime_after.get("config", ""), config_file))
    if not integrity_ok:
        classification = "FAIL"
    elif degradation <= pass_guard:
        classification = "PASS_CLEARED"
    elif degradation <= warning_guard and repeat_change <= pass_guard:
        classification = "WARNING_CONFIRMED"
    elif degradation <= warning_guard:
        classification = "WARNING_UNCONFIRMED"
    else:
        classification = "FAIL"

    props.setProperty("automatic-confirmation.performed", "true")
    props.setProperty("automatic-confirmation.result", classification)
    props.setProperty("automatic-confirmation.direction", direction)
    set_float(props, "automatic-confirmation.speed-factor", speed)
    set_float(props, "automatic-confirmation.distance.mm", distance)
    set_float(props, "automatic-confirmation.reference.mm", reference_magnitude)
    set_float(props, "automatic-confirmation.full-health.mm", previous_magnitude)
    set_float(props, "automatic-confirmation.result.mm", current_magnitude)
    set_float(props, "automatic-confirmation.degradation.mm", degradation)
    set_float(props, "automatic-confirmation.repeat-change.mm", repeat_change)
    set_float(props, "automatic-confirmation.pixel-size.mm", pixel_size_mm)
    set_float(props, "automatic-confirmation.observed-vision-noise.mm",
              observed_vision_noise)
    set_float(props, "automatic-confirmation.pass-guard.mm", pass_guard)
    set_float(props, "automatic-confirmation.warning-guard.mm", warning_guard)
    props.setProperty("automatic-confirmation.integrity.passed",
                      str(integrity_ok).lower())
    store_direction(props, "automatic-confirmation.target", target)
    store_direction(props, "automatic-confirmation.companion", companion)
    props.setProperty("automatic-confirmation.companion.direction",
                      "B" if direction == "A" else "A")
    return {
        "classification": classification,
        "direction": direction,
        "speed": speed,
        "distance": distance,
        "reference": reference_magnitude,
        "previous": previous_magnitude,
        "current": current_magnitude,
        "degradation": degradation,
        "repeat_change": repeat_change,
        "pixel_size": pixel_size_mm,
        "observed_vision_noise": observed_vision_noise,
        "pass_guard": pass_guard,
        "warning_guard": warning_guard,
        "target_scatter": target.getScatterMm(),
        "companion_direction": "B" if direction == "A" else "A",
        "companion_magnitude": companion.getMagnitudeMm(),
        "companion_scatter": companion.getScatterMm(),
        "integrity_ok": integrity_ok
    }


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


def start_health_check(status, config_file, config, reference_file, reference,
                       review_file, review, speeds, distances, repeats,
                       pixel_x_mm, pixel_y_mm, pixel_raw_mm, pixel_size_mm,
                       fresh_role=None, session=None, language="zh_CN"):
    ensure_directory(diagnostics_dir)
    ensure_directory(reports_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    if fresh_role:
        session_dir = File(required(session, "session.directory"))
        stamped = File(session_dir, fresh_role + ".properties")
        latest = stamped
    else:
        stamped = File(
            diagnostics_dir,
            "production-health-" + stamp + ".properties")
        latest = File(
            diagnostics_dir,
            "latest-production-health.properties")
    gain = float(required(config, "model.gain"))
    config_hash_before = sha256(config_file)
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty(
        "source", "external-corexy-production-health-check")
    props.setProperty("state", "running")
    props.setProperty("created", stamp)
    props.setProperty("source-production-config", config_file.getCanonicalPath())
    props.setProperty("source-production-reference",
                      reference_file.getCanonicalPath())
    props.setProperty("source-production-review", review_file.getCanonicalPath())
    if config.getProperty("production.acceptance", "") == "direct-candidate-ui":
        props.setProperty("source-production-baseline",
                          reference_file.getCanonicalPath())
        props.setProperty("source-production-candidate",
                          review_file.getCanonicalPath())
    props.setProperty("source-production-config.sha256", config_hash_before)
    props.setProperty("production.promoted-at",
                      required(config, "production.promoted-at"))
    props.setProperty("model.kind", required(config, "model.kind"))
    set_float(props, "model.gain", gain)
    props.setProperty("compensation.applied", "true")
    props.setProperty("compensation.persistence", "persistent")
    props.setProperty("stage.count", str(len(speeds)))
    props.setProperty("reference.policy",
                      required(review, "acceptance.policy"))
    if fresh_role:
        props.setProperty("session.id", required(session, "session.id"))
        props.setProperty("measurement.mode", fresh_role)
        props.setProperty("policy.sha256", required(session, "policy.sha256"))
    write_properties(props, stamped, latest)

    per_speed_total = 2 + STATIONARY_CAPTURES + len(distances) * repeats * 4
    total_steps = per_speed_total * len(speeds)
    stop_requested = AtomicBoolean(False)
    window = ProgressWindow(total_steps, stop_requested, language)
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
            for speed_index, speed in enumerate(speeds):
                listener = CombinedProgressListener(
                    window, stop_requested, speed_index, speed)
                if fresh_role == "uncompensated-primary":
                    result = CoreXYVisualDiagnostics.measureDistanceScan(
                        array(distances, 'd'), repeats, speed, SETTLE_MS,
                        STATIONARY_CAPTURES, listener)
                else:
                    result = CoreXYVisualDiagnostics.measureCompensatedDistanceScan(
                        array(distances, 'd'), repeats, speed, SETTLE_MS,
                        STATIONARY_CAPTURES, listener)
                store_stage(props, speed_index, speed, result)
                floors.append(result.getVisionDetectionFloorMm())
                floors.append(result.getStationaryVisionNoiseMm())
                for direction, values, target in [
                        ("A", list(result.getAResults()), a_values),
                        ("B", list(result.getBResults()), b_values)]:
                    for distance_index, value in enumerate(values):
                        magnitude = value.getMagnitudeMm()
                        target.append(magnitude)
                        all_values.append(magnitude)
                        original = (magnitude if fresh_role else
                                    reference_point_magnitude(
                                        reference, speed_index, direction,
                                        distance_index, speed,
                                        distances[distance_index]))
                        point_changes.append({
                            "direction": direction,
                            "speed": speed,
                            "distance": distances[distance_index],
                            "reference": original,
                            "current": magnitude,
                            "worsening": magnitude - original
                        })
                props.setProperty(
                    "completed-stage-count", str(speed_index + 1))
                write_properties(props, stamped, latest)

            reference_all = load_metrics(review, "physical.all")
            reference_a = load_metrics(review, "physical.a")
            reference_b = load_metrics(review, "physical.b")
            current_all = summarize(all_values)
            current_a = summarize(a_values)
            current_b = summarize(b_values)
            worst_point = max(
                point_changes, key=lambda value: value["worsening"])
            worst_worsening = worst_point["worsening"]
            observed_vision_noise = max(
                reference_vision_floor(reference), max(floors))
            pass_guard = pixel_size_mm * 3.0
            warning_guard = pixel_size_mm * 6.0
            config_hash_after = sha256(config_file)
            runtime_after = status_values(CoreXYScriptBootstrap.status())
            config_unchanged = config_hash_before == config_hash_after
            runtime_unchanged = (
                runtime_after.get("enabled") == "true" and
                runtime_after.get("dry-run") == "false" and
                runtime_after.get("mode") == required(config, "mode") and
                same_path(runtime_after.get("config", ""), config_file))
            pass_checks = [
                (u"全部 RMS 保持在视觉像素门限内 / All RMS within guardrail",
                 current_all["rms"] <= reference_all["rms"] + pass_guard),
                (u"B RMS 保持在视觉像素门限内 / B RMS within guardrail",
                 current_b["rms"] <= reference_b["rms"] + pass_guard),
                (u"全部 P95 保持在视觉像素门限内 / All P95 within guardrail",
                 current_all["p95"] <= reference_all["p95"] + pass_guard),
                (u"全部最大值保持在视觉像素门限内 / All maximum within guardrail",
                 current_all["maximum"] <=
                 reference_all["maximum"] + pass_guard),
                (u"同条件恶化未超过视觉像素门限 / Same-cell degradation within guardrail",
                 worst_worsening <= pass_guard),
                (u"正式配置文件保持不变 / Production configuration unchanged",
                 config_unchanged),
                (u"Agent 保持正式持久配置 / Agent retained persistent configuration",
                 runtime_unchanged)
            ]
            warning_checks = [
                current_all["rms"] <= reference_all["rms"] + warning_guard,
                current_b["rms"] <= reference_b["rms"] + warning_guard,
                current_all["p95"] <= reference_all["p95"] + warning_guard,
                current_all["maximum"] <=
                reference_all["maximum"] + warning_guard,
                worst_worsening <= warning_guard,
                config_unchanged,
                runtime_unchanged
            ]
            passed = all([value for label, value in pass_checks])
            warning = not passed and all(warning_checks)
            initial_health_result = (
                "PASS" if passed else ("WARNING" if warning else "FAIL"))
            health_result = initial_health_result
            state = "passed" if passed else (
                "warning" if warning else "failed-health")
            props.setProperty("health.initial-result", initial_health_result)
            props.setProperty("automatic-confirmation.performed", "false")
            store_metrics(props, "reference.all", reference_all)
            store_metrics(props, "reference.a", reference_a)
            store_metrics(props, "reference.b", reference_b)
            store_metrics(props, "current.all", current_all)
            store_metrics(props, "current.a", current_a)
            store_metrics(props, "current.b", current_b)
            set_float(props, "current.maximum-worsening.mm", worst_worsening)
            # Retained for compatibility with historical confirmation readers.
            set_float(props, "health.vision-floor.mm", pixel_size_mm)
            set_float(props, "health.observed-vision-noise.mm",
                      observed_vision_noise)
            set_float(props, "health.pixel-size.x.mm", pixel_x_mm)
            set_float(props, "health.pixel-size.y.mm", pixel_y_mm)
            set_float(props, "health.pixel-size.raw-maximum.mm", pixel_raw_mm)
            set_float(props, "health.pixel-size.rounded.mm", pixel_size_mm)
            set_float(props, "health.pass-guard.mm", pass_guard)
            set_float(props, "health.warning-guard.mm", warning_guard)
            props.setProperty(
                "health.policy", "production-top-camera-pixel-v1")
            props.setProperty(
                "health.guard-basis", "top-camera-units-per-pixel")
            props.setProperty("health.pass-pixels", "3")
            props.setProperty("health.warning-pixels", "6")
            props.setProperty("integrity.config-unchanged",
                              str(config_unchanged).lower())
            props.setProperty("integrity.runtime-unchanged",
                              str(runtime_unchanged).lower())
            props.setProperty("source-production-config.sha256-after",
                              config_hash_after)
            props.setProperty(
                "current.maximum-worsening.direction",
                worst_point["direction"])
            set_float(props, "current.maximum-worsening.speed-factor",
                      worst_point["speed"])
            set_float(props, "current.maximum-worsening.distance.mm",
                      worst_point["distance"])
            set_float(props, "current.maximum-worsening.reference.mm",
                      worst_point["reference"])
            set_float(props, "current.maximum-worsening.result.mm",
                      worst_point["current"])
            for index, check in enumerate(pass_checks):
                props.setProperty(
                    "health.check.{0}.passed".format(index + 1),
                    str(check[1]).lower())
                props.setProperty(
                    "health.check.{0}.description".format(index + 1),
                    check[0])

            confirmation = None
            confirmation_error = None
            if (not fresh_role and config_unchanged and runtime_unchanged and
                    worst_worsening > pass_guard):
                try:
                    confirmation = automatic_confirmation(
                        window, stop_requested, props, config_file, config,
                        config_hash_before, worst_point, pixel_size_mm)
                    local_pass = confirmation["classification"] == \
                        "PASS_CLEARED"
                    local_warning = confirmation["classification"] in \
                        ["PASS_CLEARED", "WARNING_CONFIRMED"]
                    effective_pass_checks = list(pass_checks)
                    effective_warning_checks = list(warning_checks)
                    effective_pass_checks[4] = (
                        effective_pass_checks[4][0], local_pass)
                    effective_warning_checks[4] = local_warning
                    passed = all([value for label, value in
                                  effective_pass_checks])
                    warning = (not passed and all(effective_warning_checks))
                    health_result = (
                        "PASS" if passed else
                        ("WARNING" if warning else "FAIL"))
                    state = ("passed" if passed else
                             ("warning" if warning else "failed-health"))
                except Exception as confirmation_failure:
                    if stop_requested.get():
                        raise
                    confirmation_error = str(confirmation_failure)
                    props.setProperty("automatic-confirmation.performed", "true")
                    props.setProperty("automatic-confirmation.result", "ERROR")
                    props.setProperty("automatic-confirmation.error",
                                      confirmation_error)
                    health_result = "FAIL"
                    state = "failed-health"

            props.setProperty("validation.passed", str(passed).lower())
            props.setProperty("health.result", health_result)

            lines = [
                u"OpenPnP CoreXY 正式补偿健康检查 / Production Health Check",
                u"",
                u"初始全网格结果 / Initial full-grid result: " + initial_health_result,
                u"每次测量均启用正式持久补偿 / Production compensation active throughout.",
                u"补偿比例 / Gain: {0:.0%}；持久性 / Persistence: persistent".format(
                    gain),
                u"速度 / Speeds: " + ", ".join(
                    ["{0:.0%}".format(value) for value in speeds]),
                u"距离 / Distances: " + ", ".join(
                    ["{0:.1f}".format(value) for value in distances]) + " mm",
                u"",
                metrics_line(u"全部 / All", reference_all, current_all),
                metrics_line("A", reference_a, current_a),
                metrics_line("B", reference_b, current_b),
                u"顶部相机像素尺寸 / UPP at Primary Z: X {0:.6f}, "
                u"Y {1:.6f} mm/px".format(pixel_x_mm, pixel_y_mm),
                u"门限像素尺寸 / Guard pixel: max(X,Y) {0:.6f} -> {1:.3f} mm/px；"
                u"PASS: 3 px = {2:.6f} mm；WARNING: 6 px = {3:.6f} mm".format(
                    pixel_raw_mm, pixel_size_mm, pass_guard, warning_guard),
                u"实测视觉噪声（仅诊断）/ Observed vision noise: {0:.6f} mm".format(
                    observed_vision_noise),
                u"最坏同条件恶化 / Worst same-cell degradation: {0:.6f} mm".format(
                    worst_worsening),
                u"最坏单元 / Worst cell: {0}，速度 {1:.0%}，距离 {2:.1f} mm；"
                "{3:.6f} -> {4:.6f} mm".format(
                    worst_point["direction"], worst_point["speed"],
                    worst_point["distance"], worst_point["reference"],
                    worst_point["current"]),
                u"",
                u"健康检查项 / Health checks:"
            ]
            for label, value in pass_checks:
                lines.append(("PASS  " if value else "FAIL  ") + label)
            if confirmation is not None:
                lines.extend([
                    "",
                    u"自动七次复测 / Automatic seven-repeat confirmation:",
                    u"结果 / Result: " + confirmation["classification"],
                    u"目标 / Target: {0}，速度 {1:.0%}，距离 {2:.1f} mm".format(
                        confirmation["direction"], confirmation["speed"],
                        confirmation["distance"]),
                    u"验收参考 / Accepted reference: {0:.6f} mm".format(
                        confirmation["reference"]),
                    u"全网格测量 / Full-grid measurement: {0:.6f} mm".format(
                        confirmation["previous"]),
                    u"七次复测结果 / Seven-repeat result: {0:.6f} mm".format(
                        confirmation["current"]),
                    u"相对参考恶化 / Degradation from reference: {0:+.6f} mm".format(
                        confirmation["degradation"]),
                    "Change from full-grid measurement: {0:.6f} mm".format(
                        confirmation["repeat_change"]),
                    "Confirmation pixel guard basis: {0:.3f} mm/px; "
                    "PASS guard: {1:.6f} mm; WARNING guard: {2:.6f} mm".format(
                        confirmation["pixel_size"],
                        confirmation["pass_guard"],
                        confirmation["warning_guard"]),
                    "Confirmation observed vision noise (diagnostic only): "
                    "{0:.6f} mm".format(
                        confirmation["observed_vision_noise"]),
                    "Companion {0}: magnitude {1:.6f} mm; scatter "
                    "{2:.6f} mm".format(
                        confirmation["companion_direction"],
                        confirmation["companion_magnitude"],
                        confirmation["companion_scatter"]),
                    "Confirmation configuration integrity: " +
                    ("PASS" if confirmation["integrity_ok"] else "FAIL")
                ])
            elif confirmation_error is not None:
                lines.extend([
                    "",
                    "Automatic seven-repeat confirmation: ERROR",
                    "Error: " + confirmation_error
                ])
            lines.extend([
                "",
                u"最终健康分类 / Final health classification: " + health_result,
                u"测试前后已核对正式配置哈希 / Production configuration hash checked.",
                u"未写入候选、试验、复核或正式配置 / No compensation configuration was written."
            ])
            if fresh_role:
                state = "completed"
                props.setProperty("state", state)
                props.setProperty("validation.passed", "true")
                props.setProperty("compensation.applied",
                                  "false" if fresh_role ==
                                  "uncompensated-primary" else "true")
                store_metrics(props, "current.all", current_all)
                store_metrics(props, "current.a", current_a)
                store_metrics(props, "current.b", current_b)
                lines = [
                    u"OpenPnP CoreXY 主要标定点同期采集 / Fresh Primary Scan", u"",
                    u"结果 / Result: COMPLETED",
                    u"Session: " + required(session, "session.id"),
                    u"测量模式 / Measurement mode: " + fresh_role,
                    u"补偿已应用 / Compensation applied: " + props.getProperty(
                        "compensation.applied"),
                    u"速度 / Speeds: " + ", ".join(
                        ["{0:.0%}".format(value) for value in speeds]),
                    u"距离 / Distances: " + ", ".join(
                        ["{0:.1f}".format(value) for value in distances]) + " mm",
                    u"全部 RMS/P95/最大值 / All RMS/P95/max: {0:.6f} / {1:.6f} / {2:.6f} mm".format(
                        current_all["rms"], current_all["p95"],
                        current_all["maximum"]),
                    u"A RMS: {0:.6f} mm；B RMS: {1:.6f} mm".format(
                        current_a["rms"], current_b["rms"]), u"",
                    u"此结果仅属于当前哈希锁定的同期 Session / Bound to this fresh session only.",
                    u"未修改候选或正式配置 / No candidate or formal configuration changed."
                ]
            else:
                lines.extend([u""] + health_conclusion_lines(
                    reference, props, reference_all, reference_a,
                    reference_b, current_all, current_a, current_b,
                    health_result))
        except Exception as error:
            error_text = str(error)
            state = "cancelled" if stop_requested.get() else "failed"
            lines = [
                "OpenPnP CoreXY Production Compensation Health Check",
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

        report = (File(required(session, "session.directory"),
                       fresh_role + ".txt") if fresh_role else
                  File(reports_dir, "production-health-" + stamp + ".txt"))
        report_generated = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss").format(Date())
        report_lines = [
            u"Report generated / 报告生成时间: " +
            unicode(report_generated), u""] + [unicode(line) for line in lines]
        writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
        try:
            writer.write(u"\r\n".join(report_lines))
        finally:
            writer.close()

        class ShowResult(Runnable):
            def run(inner_self):
                window.close()
                message = u"\n".join([unicode(line) for line in lines]) + \
                    u"\n\n检查结束后机器已自动 Disable。" + \
                    u"\n\n数据 / Data: " + stamped.getAbsolutePath() + \
                    u"\n报告 / Report: " + report.getAbsolutePath()
                message_type = (JOptionPane.INFORMATION_MESSAGE
                                if state in ["passed", "completed"]
                                else JOptionPane.WARNING_MESSAGE)
                JOptionPane.showMessageDialog(
                    None, message,
                    (u"CoreXY Fresh Primary Scan" if fresh_role else
                     u"CoreXY Production Health Check") if language == "en" else
                    (u"CoreXY 主要标定点同期采集" if fresh_role else
                     u"CoreXY 正式补偿健康检查"), message_type)

        SwingUtilities.invokeLater(ShowResult())

    UiUtils.submitUiMachineTask(run_validation)


def prepare_health_check():
    language = (read_ui_language() if "read_ui_language" in globals()
                else "zh_CN")
    status = status_values(CoreXYScriptBootstrap.status())
    if status.get("configured") != "true":
        raise RuntimeError("CoreXY Agent is not configured.")
    session = None
    fresh_role = None
    if FRESH_PRIMARY_SCAN:
        if not current_session_file.isFile():
            raise RuntimeError("Start a fresh same-time session first.")
        session = load_properties(current_session_file)
        if required(session, "formal.sha256").lower() != \
                sha256(File(runtime_dir, "config/corexy.properties")).lower():
            raise RuntimeError("The formal configuration changed after session start.")
        active_path = File(status.get("config", ""))
        active_props = load_properties(active_path)
        if (status.get("enabled") == "true" and
                status.get("dry-run") == "false" and
                status.get("mode") == "distance-speed-state-offset" and
                same_path(active_path, File(runtime_dir,
                                             "config/corexy.properties"))):
            fresh_role = "formal-primary"
        elif (status.get("enabled") == "false" and
              status.get("dry-run") == "false" and
              active_props.getProperty("trial.kind", "") ==
              "home-reference-baseline"):
            fresh_role = "uncompensated-primary"
        else:
            raise RuntimeError(
                "Fresh Primary scan requires the formal configuration or "
                "the Home uncompensated mode.")
    else:
        if status.get("mode") not in ["distance-speed-state-offset",
                                      "state-offset"]:
            raise RuntimeError("A supported production CoreXY profile is not active.")
        if status.get("enabled") != "true" or status.get("dry-run") != "false":
            raise RuntimeError(
                "Enable the persistent production compensation before this check.")
    if status.get("homed") != "true" or status.get("homing-bypass") != "false":
        raise RuntimeError("Enable and Home the machine before the health check.")
    if status.get("A-state") != "0" or status.get("B-state") != "0":
        raise RuntimeError("Home again so the initial A/B states are both zero.")
    config_path = status.get("config")
    if config_path is None or not str(config_path).strip():
        raise ValueError("Agent status does not contain the active config path.")
    config_file = File(runtime_dir, "config/corexy.properties")
    if not config_file.isFile():
        raise IOError("Production configuration was not found: " +
                      config_file.getAbsolutePath())
    if not FRESH_PRIMARY_SCAN and not same_path(config_path, config_file):
        raise RuntimeError(
            "The Agent is using a transient configuration, not the formal "
            "production configuration.")
    config = load_properties(config_file)
    if required(config, "production.persistence") != "persistent":
        raise ValueError("The formal configuration is not marked persistent.")
    bootstrap_baseline = (
        fresh_role == "uncompensated-primary" and
        config.getProperty("onboarding.state", "") ==
        "ready-for-calibration" and
        config.getProperty("production.acceptance", "") ==
        "bootstrap-new-machine" and
        config.getProperty("model.kind", "") == "FRESH_FIXED_VECTOR_V1")
    if (not bootstrap_baseline and
            (required(config, "enabled") != "true" or
             required(config, "dry-run") != "false")):
        raise ValueError("The formal production compensation is not active.")
    if bootstrap_baseline and (
            required(config, "enabled") != "false" or
            required(config, "dry-run") != "false"):
        raise ValueError(
            "The new-machine bootstrap profile must remain disabled.")
    gain = float(required(config, "model.gain"))
    maximum_offset = float(required(config, "safety.max-offset.mm"))
    fixed_production = (
        required(config, "mode") == "state-offset" and
        config.getProperty("model.kind", "") == "FRESH_FIXED_VECTOR_V1")
    direct_production = (fixed_production and config.getProperty(
        "production.acceptance", "") == "direct-candidate-ui")
    if bootstrap_baseline:
        # A new machine has no earlier production review. These neutral
        # reference objects satisfy the shared result writer; the fresh scan
        # is stored as a completed baseline and is not health-classified.
        reference_file = config_file
        review_file = config_file
        reference = Properties()
        reference.setProperty("state", "passed")
        set_float(reference, "model.gain", gain)
        reference.setProperty("stage.count", "1")
        reference.setProperty("stage.1.vision-detection-floor.mm", "0.0")
        reference.setProperty("stage.1.stationary-noise.mm", "0.0")
        review = Properties()
        review.setProperty("state", "passed")
        review.setProperty("validation.passed", "true")
        set_float(review, "model.gain", gain)
        review.setProperty(
            "acceptance.policy", "new-machine-bootstrap-baseline-v1")
        for direction in ["all", "a", "b"]:
            set_float(review, "physical." + direction + ".rms.mm", 0.0)
            set_float(review, "physical." + direction + ".p95.mm", 0.0)
            set_float(review, "physical." + direction + ".maximum.mm", 0.0)
    elif direct_production:
        review_file = File(required(config, "production.source-candidate"))
        reference_file = File(required(config, "production.source-baseline"))
        for path in [review_file, reference_file]:
            if not path.isFile():
                raise IOError("Production source was not found: " +
                              path.getAbsolutePath())
        if config.getProperty("production.source-candidate.sha256", "") and \
                required(config, "production.source-candidate.sha256").lower() != \
                sha256(review_file).lower():
            raise ValueError("The production candidate hash does not match.")
        if config.getProperty("production.source-baseline.sha256", "") and \
                required(config, "production.source-baseline.sha256").lower() != \
                sha256(reference_file).lower():
            raise ValueError("The uncompensated baseline hash does not match.")
        candidate = load_properties(review_file)
        reference = load_properties(reference_file)
        if (required(candidate, "source") !=
                "external-fresh-fixed-vector-analysis" or
                required(candidate, "state") != "fitted-not-applied"):
            raise ValueError("The production candidate is incomplete.")
        if not same_path(required(
                candidate, "source-uncompensated-baseline"), reference_file):
            raise ValueError(
                "The candidate and uncompensated baseline do not match.")
        if (required(reference, "state") != "completed" or
                required(reference, "compensation.applied") != "false"):
            raise ValueError("The uncompensated baseline is incomplete.")
        review = Properties()
        review.setProperty("acceptance.policy", "direct-candidate-health-v1")
        review.setProperty("state", "passed")
        review.setProperty("validation.passed", "true")
        set_float(review, "model.gain", gain)
        for direction in ["all", "a", "b"]:
            store_metrics(review, "physical." + direction,
                          load_metrics(reference, "current." + direction))
    else:
        review_file = File(required(config, "production.source-review"))
        reference_file = File(required(
            config, "production.source-physical-validation"))
        for path in [review_file, reference_file]:
            if not path.isFile():
                raise IOError("Production evidence was not found: " +
                              path.getAbsolutePath())
        review = load_properties(review_file)
        reference = load_properties(reference_file)
    manual_production = (fixed_production and config.getProperty(
        "production.acceptance", "") == "user-manual-ui")
    system_passed = (required(review, "state") == "passed" and
                     required(review, "validation.passed") == "true")
    if not direct_production and not system_passed and not manual_production:
        if not fixed_production or config.getProperty(
                "production.acceptance", "") != \
                "user-accepted-with-deviation":
            raise ValueError("The production review is not passed evidence.")
        acceptance_file = File(required(
            config, "production.source-user-acceptance"))
        if not acceptance_file.isFile() or required(
                load_properties(acceptance_file), "state") != \
                "user-accepted-with-deviation":
            raise ValueError("Fixed production user acceptance is missing.")
    if (not direct_production and required(reference, "state") not in
            ["passed", "failed-acceptance"]):
        raise ValueError("The production physical reference is incomplete.")
    if not direct_production and not manual_production and (abs(float(required(
            review, "model.gain")) - gain) > 0.000001 or
            abs(float(required(reference, "model.gain")) - gain) > 0.000001):
        raise ValueError("Production gain and validation evidence do not match.")
    if fresh_role and session.getProperty("calibration.distances.mm"):
        speeds = [float(value.strip()) for value in required(
            session, "calibration.speeds").split(",")]
        distances = [float(value.strip()) for value in required(
            session, "calibration.distances.mm").split(",")]
        repeats = int(required(session, "calibration.repeats"))
    else:
        speeds, distances, repeats = load_reference_grid(reference)

    if not fixed_production:
        model_range = min(
            float(required(config, "model.a.maximum-distance.mm")),
            float(required(config, "model.b.maximum-distance.mm")))
        if max(distances) > model_range + 0.000001:
            raise RuntimeError(
                "The saved health-check grid exceeds the formal A/B model range.")

    machine = Configuration.get().getMachine()
    if not machine.isEnabled() or not machine.isHomed():
        raise RuntimeError("Enable and Home the machine before the health check.")
    head = machine.getDefaultHead()
    primary = head.getCalibrationPrimaryFiducialLocation()
    pixel_x_mm, pixel_y_mm, pixel_raw_mm, pixel_size_mm = \
        top_camera_pixel_size_mm(machine, primary)
    half = max(distances) * 0.5
    pixel_summary = ((
        u"Top-camera UPP at Primary Z: X {0:.6f}, Y {1:.6f} mm/px; "
        u"guard pixel {2:.3f} mm/px; PASS {3:.3f} mm; WARNING {4:.3f} mm")
        if language == "en" else (
        u"顶部相机在 Primary Z 的 UPP：X {0:.6f}、Y {1:.6f} mm/px；"
        u"门限像素尺寸 {2:.3f} mm/px；PASS {3:.3f} mm；WARNING {4:.3f} mm"
    )).format(pixel_x_mm, pixel_y_mm, pixel_size_mm,
              pixel_size_mm * 3.0, pixel_size_mm * 6.0)
    operation_name = (u"采集同期未补偿主要标定点基线"
                      if FRESH_PRIMARY_SCAN else u"正式 CoreXY 补偿健康检查")
    if bootstrap_baseline:
        comparison_text_en = (
            u"This is the first uncompensated baseline for this machine; "
            u"no earlier production result is used for comparison.")
        comparison_text_zh = (
            u"本次采集用于建立该机器的首份同期未补偿基线，"
            u"不与任何旧生产结果比较。")
    else:
        reference_name = (u"同期未补偿基线" if direct_production else
                          u"已验收的物理参考")
        comparison_text_en = (
            u"The measurement will be compared with {0}.".format(
                u"the same-session uncompensated baseline"
                if direct_production else u"the accepted physical reference"))
        comparison_text_zh = u"本次测量将与{0}比较。".format(reference_name)
    message = ((
        u"{11}\n\n"
        u"Compensation: {0:.0%}; maximum combined correction: {1:.3f} mm\n"
        u"Speeds: {2}\nDistances: {3} mm\n"
        u"Grid cells: {4}; visual return measurements: {5}\n"
        u"XY envelope: X {6:.1f} to {7:.1f}, Y {8:.1f} to {9:.1f} mm\n\n"
        u"{10}\n\n"
        u"{12} A cell above the PASS "
        u"guard is automatically repeated seven times before the final result.\n"
        u"The production configuration hash is checked before and after. No "
        u"candidate, trial, review or production configuration is written.\n"
        u"The machine is automatically Disabled when the test ends.\n\n"
        u"Clear the table and remove all nozzles before running.\n\n"
        u"Click OK to start or Cancel to return."
    ) if language == "en" else (
        u"{11}\n\n"
        u"补偿比例：{0:.0%}；最大合成修正：{1:.3f} mm\n"
        u"速度：{2}\n距离：{3} mm\n"
        u"测试单元：{4}；视觉回归测量：{5} 次\n"
        u"XY 范围：X {6:.1f} 至 {7:.1f}，Y {8:.1f} 至 {9:.1f} mm\n\n"
        u"{10}\n\n"
        u"{12}超过 PASS 线的局部单元会自动复测 7 次，"
        u"然后合并给出结果。\n"
        u"测试前后会核对正式配置哈希，不会写入候选、试验、复核或正式配置。\n"
        u"测试结束后机器会自动 Disable。\n\n"
        u"请提前检查台面有无障碍物、收纳撤下所有吸嘴，保障运行安全。\n\n"
        u"点击“确定”开始，点击“取消”返回。"
    )).format(
        gain, maximum_offset,
        ", ".join(["{0:.0%}".format(value) for value in speeds]),
        ", ".join(["{0:.1f}".format(value) for value in distances]),
        len(speeds) * len(distances),
        len(speeds) * len(distances) * repeats * 4,
        primary.getX() - half, primary.getX() + half,
        primary.getY() - half, primary.getY() + half,
        pixel_summary,
        (u"Capture Same-session Uncompensated Primary Baseline"
         if FRESH_PRIMARY_SCAN else u"Production CoreXY Health Check")
        if language == "en" else operation_name,
        comparison_text_en if language == "en" else comparison_text_zh)
    confirmation = JOptionPane.showConfirmDialog(
        None, message,
        ((u"Fresh Primary Baseline" if FRESH_PRIMARY_SCAN else
          u"Production CoreXY Health Check") if language == "en" else
         operation_name),
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
    if confirmation != JOptionPane.OK_OPTION:
        return
    start_health_check(
        status, config_file, config, reference_file, reference,
        review_file, review, speeds, distances, repeats,
        pixel_x_mm, pixel_y_mm, pixel_raw_mm, pixel_size_mm,
        fresh_role, session, language)


try:
    prepare_health_check()
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Production Health Check Error",
        JOptionPane.ERROR_MESSAGE)
    raise
