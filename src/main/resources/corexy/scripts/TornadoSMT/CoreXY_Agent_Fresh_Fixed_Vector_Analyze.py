import math
from jarray import zeros
from java.io import (File, FileInputStream, FileOutputStream,
                     OutputStreamWriter)
from java.security import MessageDigest
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
formal_file = File(runtime_dir, "config/corexy.properties")
policy_file = File(runtime_dir, "config/calibration-policy.properties")
current_session_file = File(runtime_dir, "sessions/current-session.properties")
candidates_dir = File(runtime_dir, "candidates")
reports_dir = File(runtime_dir, "reports")


def ensure_directory(path):
    if not path.isDirectory() and not path.mkdirs():
        raise IOError("Cannot create directory: " + path.getAbsolutePath())


def load_properties(path):
    props = Properties()
    stream = FileInputStream(path)
    try:
        props.load(stream)
    finally:
        stream.close()
    return props


def write_properties(props, path, comment):
    stream = FileOutputStream(path)
    try:
        props.store(stream, comment)
    finally:
        stream.close()


def required(props, key):
    value = props.getProperty(key)
    if value is None or not str(value).strip():
        raise ValueError("Missing property: " + key)
    return str(value).strip()


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


def median(values):
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return 0.5 * (ordered[middle - 1] + ordered[middle])


def read_points(baseline, direction):
    points = []
    stage_count = int(required(baseline, "stage.count"))
    for stage_index in range(stage_count):
        stage = "stage.{0}".format(stage_index + 1)
        speed = float(required(baseline, stage + ".speed-factor"))
        count = int(required(
            baseline, stage + "." + direction + ".distance.count"))
        for distance_index in range(count):
            prefix = stage + "." + direction + ".distance.{0}".format(
                distance_index + 1)
            points.append({
                "speed": speed,
                "distance": float(required(
                    baseline, prefix + ".virtual-distance.mm")),
                "x": float(required(baseline, prefix + ".correction.x.mm")),
                "y": float(required(baseline, prefix + ".correction.y.mm")),
                "magnitude": float(required(
                    baseline, prefix + ".magnitude.mm")),
                "scatter": float(required(baseline, prefix + ".scatter.mm"))
            })
    if not points:
        raise ValueError("The fresh baseline contains no " + direction.upper() +
                         " measurements.")
    return points


def predicted_metrics(points, threshold, offset_x, offset_y, gain):
    residuals = []
    for point in points:
        active = point["distance"] >= threshold
        x = point["x"] - (offset_x * gain if active else 0.0)
        y = point["y"] - (offset_y * gain if active else 0.0)
        residuals.append(math.hypot(x, y) + point["scatter"])
    ordered = sorted(residuals)
    rms = math.sqrt(sum([value * value for value in residuals]) /
                    len(residuals))
    p95 = ordered[max(0, int(math.ceil(len(ordered) * 0.95)) - 1)]
    return rms, p95, ordered[-1]


def fit_direction(points, diagnostic_floor):
    baseline_rms = math.sqrt(sum([
        point["magnitude"] * point["magnitude"] for point in points
    ]) / len(points))
    distances = sorted(set([point["distance"] for point in points]))
    if baseline_rms <= diagnostic_floor:
        return {
            "threshold": distances[-1] + diagnostic_floor,
            "x": 0.0, "y": 0.0, "disabled": True,
            "baseline_rms": baseline_rms,
            "expected_rms": baseline_rms,
            "expected_maximum": max([point["magnitude"] for point in points])
        }

    best = None
    for threshold in distances[:-1]:
        active = [point for point in points
                  if point["distance"] >= threshold]
        if len(set([point["distance"] for point in active])) < 2:
            continue
        vectors = [(point["x"], point["y"]) for point in active]
        candidates = list(vectors)
        for first in range(len(vectors)):
            for second in range(first + 1, len(vectors)):
                candidates.append((
                    0.5 * (vectors[first][0] + vectors[second][0]),
                    0.5 * (vectors[first][1] + vectors[second][1])))
        candidates.append((
            median([value[0] for value in vectors]),
            median([value[1] for value in vectors])))
        for offset_x, offset_y in candidates:
            rms, p95, maximum = predicted_metrics(
                points, threshold, offset_x, offset_y, 1.0)
            score = (maximum, rms, math.hypot(offset_x, offset_y))
            if best is None or score < best[0]:
                best = (score, {
                    "threshold": threshold,
                    "x": offset_x, "y": offset_y, "disabled": False,
                    "baseline_rms": baseline_rms,
                    "expected_rms": rms,
                    "expected_maximum": maximum
                })
    if best is None:
        raise ValueError("No stable fixed-vector fit could be generated.")
    return best[1]


def set_float(props, key, value):
    props.setProperty(key, "{0:.9f}".format(float(value)))


try:
    for path in [formal_file, policy_file, current_session_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    session = load_properties(current_session_file)
    baseline_file = File(required(session, "session.directory"),
                         "uncompensated-primary.properties")
    if not baseline_file.isFile():
        raise IOError("Run T0-2 to collect a fresh uncompensated Primary baseline.")
    baseline = load_properties(baseline_file)
    if required(baseline, "state") != "completed" or \
            required(baseline, "measurement.mode") != "uncompensated-primary" or \
            required(baseline, "compensation.applied") != "false":
        raise ValueError("The current session baseline is not a completed "
                         "uncompensated Primary scan.")
    if required(session, "formal.sha256").lower() != sha256(formal_file).lower():
        raise ValueError("The formal configuration changed during this session.")
    if required(session, "policy.sha256").lower() != sha256(policy_file).lower():
        raise ValueError("The calibration policy changed during this session.")

    formal = load_properties(formal_file)
    floors = []
    for index in range(int(required(baseline, "stage.count"))):
        prefix = "stage.{0}".format(index + 1)
        floors.append(float(required(
            baseline, prefix + ".vision-detection-floor.mm")))
        floors.append(float(required(
            baseline, prefix + ".stationary-noise.mm")))
    vision_floor = max(floors)
    user_floor = float(session.getProperty(
        "target.minimum-trusted-displacement.mm", "0.015"))
    diagnostic_floor = max(vision_floor * 3.5, user_floor)
    a_points = read_points(baseline, "a")
    b_points = read_points(baseline, "b")
    a_fit = fit_direction(a_points, diagnostic_floor)
    b_fit = fit_direction(b_points, diagnostic_floor)

    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("state", "fitted-not-applied")
    props.setProperty("source", "external-fresh-fixed-vector-analysis")
    props.setProperty("created", stamp)
    props.setProperty("machine.scope", "local-only")
    props.setProperty("session.id", required(session, "session.id"))
    props.setProperty("source-uncompensated-baseline",
                      baseline_file.getCanonicalPath())
    props.setProperty("source-uncompensated-baseline.sha256",
                      sha256(baseline_file))
    props.setProperty("source-formal.sha256", sha256(formal_file))
    props.setProperty("source-policy.sha256", sha256(policy_file))
    props.setProperty("model.kind", "FRESH_FIXED_VECTOR_V1")
    props.setProperty("target.radial-tolerance.mm", session.getProperty(
        "target.radial-tolerance.mm", "0.050000000"))
    set_float(props, "target.minimum-trusted-displacement.mm", user_floor)
    props.setProperty("corexy_x_axis_id", required(formal, "axis.x.id"))
    props.setProperty("corexy_y_axis_id", required(formal, "axis.y.id"))
    set_float(props, "corexy_min_move_a_mm", a_fit["threshold"])
    set_float(props, "corexy_min_move_b_mm", b_fit["threshold"])
    set_float(props, "corexy_a_positive_x_mm", a_fit["x"])
    set_float(props, "corexy_a_positive_y_mm", a_fit["y"])
    set_float(props, "corexy_b_positive_x_mm", b_fit["x"])
    set_float(props, "corexy_b_positive_y_mm", b_fit["y"])
    props.setProperty("corexy_speed_factor", "0.250000000")
    props.setProperty("fit.a.disabled", str(a_fit["disabled"]).lower())
    props.setProperty("fit.b.disabled", str(b_fit["disabled"]).lower())
    set_float(props, "vision-floor.mm", vision_floor)
    set_float(props, "diagnostic-floor.mm", diagnostic_floor)
    for direction, fit, points in [
            ("a", a_fit, a_points), ("b", b_fit, b_points)]:
        set_float(props, "fit." + direction + ".baseline-rms.mm",
                  fit["baseline_rms"])
        set_float(props, "fit." + direction + ".expected-rms-100.mm",
                  fit["expected_rms"])
        set_float(props, "fit." + direction + ".expected-maximum-100.mm",
                  fit["expected_maximum"])
    for gain_percent in [25, 50, 75, 100]:
        gain = gain_percent / 100.0
        all_residuals = []
        for fit, points in [(a_fit, a_points), (b_fit, b_points)]:
            values = predicted_metrics(
                points, fit["threshold"], fit["x"], fit["y"], gain)
            all_residuals.append(values)
        prefix = "prediction.{0}".format(gain_percent)
        set_float(props, prefix + ".maximum.mm",
                  max([value[2] for value in all_residuals]))

    ensure_directory(candidates_dir)
    ensure_directory(reports_dir)
    stamped = File(candidates_dir,
                   "fresh-fixed-vector-" + stamp + ".properties")
    latest = File(candidates_dir, "latest-fresh-fixed-vector.properties")
    write_properties(props, stamped, "Fresh fixed A/B vector candidate")
    write_properties(props, latest, "Fresh fixed A/B vector candidate")
    session.setProperty("session.state", "candidate-ready")
    session.setProperty("candidate.path", stamped.getCanonicalPath())
    session.setProperty("candidate.created", stamp)
    write_properties(session, current_session_file,
                     "OpenPnP CoreXY fresh dual-anchor session")
    session_manifest = File(required(session, "session.directory"),
                            "manifest.properties")
    write_properties(session, session_manifest,
                     "OpenPnP CoreXY fresh dual-anchor session")

    lines = [
        u"OpenPnP CoreXY 同期固定 A+/B+ 向量分析 / Fresh Fixed-vector Analysis", u"",
        u"Session: " + required(session, "session.id"),
        u"未补偿基线 / Source baseline: " + baseline_file.getCanonicalPath(),
        u"视觉噪声 / Vision floor: {0:.6f} mm；诊断底线 / Diagnostic floor: {1:.6f} mm".format(
            vision_floor, diagnostic_floor), "",
        u"A+：阈值 / threshold {0:.3f} mm；向量 / vector ({1:+.6f}, {2:+.6f}) mm；{3}".format(
            a_fit["threshold"], a_fit["x"], a_fit["y"],
            u"停用 / DISABLED" if a_fit["disabled"] else u"启用 / ACTIVE"),
        u"B+：阈值 / threshold {0:.3f} mm；向量 / vector ({1:+.6f}, {2:+.6f}) mm；{3}".format(
            b_fit["threshold"], b_fit["x"], b_fit["y"],
            u"停用 / DISABLED" if b_fit["disabled"] else u"启用 / ACTIVE"), u"",
        u"候选仅来自当前同期 Session / Candidate derived from this session only.",
        u"分析过程没有运动，也没有修改配置 / No movement or configuration change.",
        u"默认补偿比例为100%；若观察到过补偿，可改用25%、50%或75%。"
    ]
    report = File(reports_dir,
                  "fresh-fixed-vector-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
    try:
        writer.write(u"\r\n".join(lines))
    finally:
        writer.close()
    JOptionPane.showMessageDialog(
        None, u"\n".join(lines) + u"\n\n候选 / Candidate: " +
        stamped.getAbsolutePath() + u"\n报告 / Report: " + report.getAbsolutePath(),
        u"CoreXY 同期固定向量分析",
        JOptionPane.INFORMATION_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Fresh Fixed-vector Analysis Error",
        JOptionPane.ERROR_MESSAGE)
    raise
