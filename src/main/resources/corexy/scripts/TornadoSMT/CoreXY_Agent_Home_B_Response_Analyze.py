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
diagnostics_dir = File(runtime_dir, "diagnostics")
reports_dir = File(runtime_dir, "reports")
baseline_file = File(
    diagnostics_dir, "latest-home-reference-baseline.properties")


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


def number(props, key):
    return float(required(props, key))


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


def probe_file(label):
    direct = File(diagnostics_dir,
                  "latest-home-response-probe-" + label + ".properties")
    if direct.isFile():
        return direct
    legacy_name = None
    if label == "x-plus":
        legacy_name = "latest-home-response-probe-x.properties"
    elif label == "y-minus":
        legacy_name = "latest-home-response-probe-y.properties"
    if legacy_name is None:
        return direct
    legacy = File(diagnostics_dir, legacy_name)
    if not legacy.isFile():
        return direct
    props = load_properties(legacy)
    component = number(props, "probe.offset." + label[0] + ".mm")
    if (label.endswith("plus") and component > 0.0) or \
            (label.endswith("minus") and component < 0.0):
        return legacy
    return direct


def validate_measurement(props, label, revision, config_hash,
                         speed, distance):
    mode = required(props, "measurement.mode")
    allowed = ["response-probe-" + label]
    if label == "x-plus":
        allowed.append("response-probe-x")
    elif label == "y-minus":
        allowed.append("response-probe-y")
    if mode not in allowed:
        raise ValueError(label + " probe has unexpected mode: " + mode)
    if required(props, "production.revision") != revision:
        raise ValueError(label + " probe belongs to another revision.")
    if required(props, "source-production-config.sha256").lower() != \
            config_hash.lower():
        raise ValueError(label + " probe belongs to another production file.")
    if required(props, "integrity.config-unchanged") != "true":
        raise ValueError(label + " probe failed its integrity check.")
    if abs(number(props, "test.speed-factor") - speed) > 0.000001 or \
            abs(number(props, "test.distance.mm") - distance) > 0.001:
        raise ValueError(label + " probe does not match baseline conditions.")
    component = number(props, "probe.offset." + label[0] + ".mm")
    if label.endswith("plus") and component <= 0.0:
        raise ValueError(label + " probe offset is not positive.")
    if label.endswith("minus") and component >= 0.0:
        raise ValueError(label + " probe offset is not negative.")


def vector(props):
    return (number(props, "result.b-state-difference.x.mm"),
            number(props, "result.b-state-difference.y.mm"))


def subtract(left, right):
    return left[0] - right[0], left[1] - right[1]


def magnitude(value):
    return math.hypot(value[0], value[1])


def residual(hxx, hxy, hyx, hyy, baseline, x, y):
    rx = baseline[0] - hxx * x - hxy * y
    ry = baseline[1] - hyx * x - hyy * y
    return rx, ry, math.hypot(rx, ry)


def solve_candidate(matrix, baseline, target, maximum):
    hxx, hxy, hyx, hyy = matrix
    best = (0.0, 0.0, magnitude(baseline), baseline[0], baseline[1])
    radial_step = max(0.00025, maximum / 400.0)
    radius = 0.0
    while radius <= maximum + 0.0000001:
        ring_best = None
        for index in range(720):
            angle = 2.0 * math.pi * index / 720.0
            x = radius * math.cos(angle)
            y = radius * math.sin(angle)
            rx, ry, radial = residual(hxx, hxy, hyx, hyy, baseline, x, y)
            item = (x, y, radial, rx, ry)
            if ring_best is None or radial < ring_best[2]:
                ring_best = item
            if radial < best[2]:
                best = item
        if ring_best[2] <= target:
            return ring_best, True
        radius += radial_step
    return best, False


def write_properties(props, stamped, latest):
    for path in [stamped, latest]:
        stream = FileOutputStream(path)
        try:
            props.store(stream, "CoreXY Home centered four-probe analysis")
        finally:
            stream.close()


try:
    labels = ["x-plus", "x-minus", "y-plus", "y-minus"]
    probe_paths = dict([(label, probe_file(label)) for label in labels])
    for path in [formal_file, policy_file, baseline_file] + \
            list(probe_paths.values()):
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())

    formal = load_properties(formal_file)
    policy = load_properties(policy_file)
    baseline_props = load_properties(baseline_file)
    probes = dict([(label, load_properties(probe_paths[label]))
                   for label in labels])
    config_hash = sha256(formal_file)
    revision = required(formal, "production.promoted-at")
    speed = number(baseline_props, "test.speed-factor")
    distance = number(baseline_props, "test.distance.mm")
    if required(baseline_props, "measurement.mode") != \
            "uncompensated-baseline":
        raise ValueError("The Home reference is not an uncompensated baseline.")
    if required(baseline_props, "production.revision") != revision or \
            required(baseline_props,
                     "source-production-config.sha256").lower() != \
            config_hash.lower():
        raise ValueError("The Home baseline belongs to another revision.")
    if required(baseline_props, "integrity.config-unchanged") != "true":
        raise ValueError("The Home baseline failed its integrity check.")
    for label in labels:
        validate_measurement(
            probes[label], label, revision, config_hash, speed, distance)

    baseline = vector(baseline_props)
    xp = vector(probes["x-plus"])
    xm = vector(probes["x-minus"])
    yp = vector(probes["y-plus"])
    ym = vector(probes["y-minus"])
    qxp = number(probes["x-plus"], "probe.offset.x.mm")
    qxm = number(probes["x-minus"], "probe.offset.x.mm")
    qyp = number(probes["y-plus"], "probe.offset.y.mm")
    qym = number(probes["y-minus"], "probe.offset.y.mm")

    hxx = (xm[0] - xp[0]) / (qxp - qxm)
    hyx = (xm[1] - xp[1]) / (qxp - qxm)
    hxy = (ym[0] - yp[0]) / (qyp - qym)
    hyy = (ym[1] - yp[1]) / (qyp - qym)
    matrix = (hxx, hxy, hyx, hyy)
    hx_plus = ((baseline[0] - xp[0]) / qxp,
               (baseline[1] - xp[1]) / qxp)
    hx_minus = ((baseline[0] - xm[0]) / qxm,
                (baseline[1] - xm[1]) / qxm)
    hy_plus = ((baseline[0] - yp[0]) / qyp,
               (baseline[1] - yp[1]) / qyp)
    hy_minus = ((baseline[0] - ym[0]) / qym,
                (baseline[1] - ym[1]) / qym)
    bias_x = ((xp[0] + xm[0]) * 0.5 - baseline[0],
              (xp[1] + xm[1]) * 0.5 - baseline[1])
    bias_y = ((yp[0] + ym[0]) * 0.5 - baseline[0],
              (yp[1] + ym[1]) * 0.5 - baseline[1])
    asymmetry_x = magnitude(subtract(hx_plus, hx_minus))
    asymmetry_y = magnitude(subtract(hy_plus, hy_minus))

    determinant = hxx * hyy - hxy * hyx
    trace = hxx * hxx + hxy * hxy + hyx * hyx + hyy * hyy
    discriminant = max(0.0, trace * trace - 4.0 * determinant * determinant)
    lambda_max = 0.5 * (trace + math.sqrt(discriminant))
    lambda_min = 0.5 * (trace - math.sqrt(discriminant))
    condition = (math.sqrt(lambda_max / lambda_min)
                 if lambda_min > 0.000000001 else float("inf"))

    detection_floor = number(baseline_props, "vision.detection-floor.mm")
    x_scatter = max(number(probes["x-plus"], "result.scatter.mm"),
                    number(probes["x-minus"], "result.scatter.mm"))
    y_scatter = max(number(probes["y-plus"], "result.scatter.mm"),
                    number(probes["y-minus"], "result.scatter.mm"))
    x_effect = magnitude(subtract(xm, xp)) * 0.5
    y_effect = magnitude(subtract(ym, yp)) * 0.5
    x_guard = max(detection_floor * 2.0, x_scatter)
    y_guard = max(detection_floor * 2.0, y_scatter)
    x_signal_ratio = x_effect / x_guard
    y_signal_ratio = y_effect / y_guard
    x_bias_ratio = magnitude(bias_x) / x_guard
    y_bias_ratio = magnitude(bias_y) / y_guard

    reasons = []
    weak = False
    if math.hypot(hxx, hyx) < 0.20:
        reasons.append("centered B-X response is too small")
        weak = True
    if math.hypot(hxy, hyy) < 0.20:
        reasons.append("centered B-Y response is too small")
        weak = True
    if abs(determinant) < 0.10:
        reasons.append("centered response matrix is close to singular")
        weak = True
    if condition > 10.0:
        reasons.append("centered response matrix condition exceeds 10")
        weak = True
    if min(x_signal_ratio, y_signal_ratio) < 1.0:
        reasons.append("one or more centered probe signals are below noise")
    if max(x_bias_ratio, y_bias_ratio) > 1.0:
        reasons.append("positive and negative responses are asymmetric")
    if weak:
        quality = "WEAK"
    elif min(x_signal_ratio, y_signal_ratio) < 1.0 or \
            max(x_bias_ratio, y_bias_ratio) > 1.0:
        quality = "PROVISIONAL"
    else:
        quality = "GOOD"

    target = number(policy, "target.radial-tolerance.mm")
    safety = number(formal, "safety.max-offset.mm")
    reserve_factor = float(policy.getProperty(
        "home-reference.response-safety-factor", "0.900"))
    if reserve_factor <= 0.5 or reserve_factor > 1.0:
        raise ValueError("Home response safety factor must be in (0.5, 1.0].")
    maximum = safety * reserve_factor
    solution, target_feasible = solve_candidate(
        matrix, baseline, target, maximum)
    solution_x, solution_y, predicted_radial, predicted_x, predicted_y = \
        solution

    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    analysis_id = "centered-four-probe-v1-" + stamp
    props = Properties()
    props.setProperty("schema.version", "2")
    props.setProperty("source", "home-b-state-response-analysis")
    props.setProperty("state", "analyzed-not-applied")
    props.setProperty("created", stamp)
    props.setProperty("analysis.id", analysis_id)
    props.setProperty("analysis.method", "centered-four-probe-v1")
    props.setProperty("production.revision", revision)
    props.setProperty("source-production-config", formal_file.getCanonicalPath())
    props.setProperty("source-production-config.sha256", config_hash)
    props.setProperty("source-baseline", baseline_file.getCanonicalPath())
    props.setProperty("source-baseline.sha256", sha256(baseline_file))
    for label in labels:
        props.setProperty("source-probe." + label,
                          probe_paths[label].getCanonicalPath())
        props.setProperty("source-probe." + label + ".sha256",
                          sha256(probe_paths[label]))
    set_float(props, "test.speed-factor", speed)
    set_float(props, "test.distance.mm", distance)
    set_float(props, "baseline.x.mm", baseline[0])
    set_float(props, "baseline.y.mm", baseline[1])
    set_float(props, "baseline.magnitude.mm", magnitude(baseline))
    for key, value in [("matrix.hxx", hxx), ("matrix.hxy", hxy),
                       ("matrix.hyx", hyx), ("matrix.hyy", hyy),
                       ("matrix.determinant", determinant)]:
        set_float(props, key, value)
    props.setProperty("matrix.condition", "infinite" if math.isinf(condition)
                      else "{0:.9f}".format(condition))
    set_float(props, "identification.x-signal-ratio", x_signal_ratio)
    set_float(props, "identification.y-signal-ratio", y_signal_ratio)
    for axis, bias, asymmetry in [("x", bias_x, asymmetry_x),
                                  ("y", bias_y, asymmetry_y)]:
        set_float(props, "identification." + axis + "-bias.x.mm", bias[0])
        set_float(props, "identification." + axis + "-bias.y.mm", bias[1])
        set_float(props, "identification." + axis + "-bias.magnitude.mm",
                  magnitude(bias))
        set_float(props, "identification." + axis +
                  "-asymmetry.mm-per-mm", asymmetry)
    props.setProperty("identification.quality", quality)
    props.setProperty("identification.reasons", "; ".join(reasons))
    set_float(props, "target.radial-tolerance.mm", target)
    set_float(props, "safety.configured-max-offset.mm", safety)
    set_float(props, "safety.reserve-factor", reserve_factor)
    set_float(props, "safety.analysis-max-offset.mm", maximum)
    set_float(props, "prediction.minimum-validation-guard.mm",
              detection_floor * 2.0)
    props.setProperty("solution.target-feasible", str(target_feasible).lower())
    props.setProperty("solution.kind",
                      "minimum-necessary" if target_feasible else "best-safe")
    set_float(props, "solution.offset.x.mm", solution_x)
    set_float(props, "solution.offset.y.mm", solution_y)
    set_float(props, "solution.offset.magnitude.mm",
              math.hypot(solution_x, solution_y))
    set_float(props, "solution.predicted-residual.x.mm", predicted_x)
    set_float(props, "solution.predicted-residual.y.mm", predicted_y)
    set_float(props, "solution.predicted-residual.mm", predicted_radial)

    gains = [0.25, 0.50, 0.75, 1.00]
    props.setProperty("candidate.count", str(len(gains)))
    candidate_lines = []
    for index, gain in enumerate(gains, 1):
        x = solution_x * gain
        y = solution_y * gain
        rx, ry, radial = residual(hxx, hxy, hyx, hyy, baseline, x, y)
        prefix = "candidate.{0}".format(index)
        set_float(props, prefix + ".gain", gain)
        set_float(props, prefix + ".offset.x.mm", x)
        set_float(props, prefix + ".offset.y.mm", y)
        set_float(props, prefix + ".offset.magnitude.mm", math.hypot(x, y))
        set_float(props, prefix + ".predicted-residual.x.mm", rx)
        set_float(props, prefix + ".predicted-residual.y.mm", ry)
        set_float(props, prefix + ".predicted-residual.mm", radial)
        candidate_lines.append(
            "{0:.0%}: offset ({1:+.6f}, {2:+.6f}) mm; predicted "
            "residual ({3:+.6f}, {4:+.6f}), radial {5:.6f} mm".format(
                gain, x, y, rx, ry, radial))

    if not diagnostics_dir.isDirectory() and not diagnostics_dir.mkdirs():
        raise IOError("Cannot create diagnostics directory.")
    if not reports_dir.isDirectory() and not reports_dir.mkdirs():
        raise IOError("Cannot create reports directory.")
    stamped = File(
        diagnostics_dir, "home-response-analysis-" + stamp + ".properties")
    latest = File(diagnostics_dir, "latest-home-response-analysis.properties")
    write_properties(props, stamped, latest)

    lines = [
        "OpenPnP CoreXY Home B-state Centered Response Analysis", "",
        "No movement occurred and no configuration was changed.",
        "Method: four directional probes with centered differences",
        "Analysis ID: " + analysis_id,
        "Production revision: " + revision,
        "Test: speed {0:.0%}, distance {1:.1f} mm".format(speed, distance),
        "Baseline B vector: ({0:+.6f}, {1:+.6f}) mm; radial {2:.6f} mm".format(
            baseline[0], baseline[1], magnitude(baseline)), "",
        "Centered response matrix H, residual = baseline - H * offset:",
        "  [{0:+.4f}  {1:+.4f}]".format(hxx, hxy),
        "  [{0:+.4f}  {1:+.4f}]".format(hyx, hyy),
        "Determinant {0:+.6f}; condition {1}".format(
            determinant, "infinite" if math.isinf(condition) else
            "{0:.3f}".format(condition)),
        "Identification quality: " + quality,
        "Centered signal/noise: X {0:.3f}, Y {1:.3f}".format(
            x_signal_ratio, y_signal_ratio),
        "Direction-asymmetry bias: X {0:.6f}, Y {1:.6f} mm".format(
            magnitude(bias_x), magnitude(bias_y)),
    ]
    if reasons:
        lines.append("Reasons: " + "; ".join(reasons))
    lines += ["", "User target: {0:.6f} mm".format(target),
              "Safety maximum: {0:.6f} mm; analysis reserve: {1:.6f} mm".format(
                  safety, maximum),
              "Solution: {0}; target feasible: {1}".format(
                  "minimum necessary" if target_feasible else "best safe",
                  str(target_feasible).lower()),
              "Solution offset: ({0:+.6f}, {1:+.6f}) mm; magnitude "
              "{2:.6f} mm".format(solution_x, solution_y,
                                   math.hypot(solution_x, solution_y)),
              "Predicted residual: ({0:+.6f}, {1:+.6f}) mm; radial "
              "{2:.6f} mm".format(predicted_x, predicted_y,
                                   predicted_radial), "",
              "Validation candidates:"] + candidate_lines + ["",
              "Candidates are analysis-only. Fresh measurements must match "
              "this analysis ID and pass vector prediction checks."]
    report = File(reports_dir, "home-response-analysis-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
    try:
        writer.write("\r\n".join(lines))
    finally:
        writer.close()
    JOptionPane.showMessageDialog(
        None, "\n".join(lines) + "\n\nData: " + stamped.getAbsolutePath() +
        "\nReport: " + report.getAbsolutePath(),
        "CoreXY Home Response Analysis",
        JOptionPane.INFORMATION_MESSAGE if quality == "GOOD" else
        JOptionPane.WARNING_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Home Response Analysis Error",
        JOptionPane.ERROR_MESSAGE)
    raise
