import math
from jarray import zeros
from java.io import (File, FileInputStream, FileOutputStream,
                     OutputStreamWriter)
from java.security import MessageDigest
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration, LengthUnit


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
formal_file = File(runtime_dir, "config/corexy.properties")
policy_file = File(runtime_dir, "config/calibration-policy.properties")
scan_file = File(
    runtime_dir, "diagnostics/latest-home-b-surface-scan.properties")
response_file = File(
    runtime_dir, "diagnostics/latest-home-response-analysis.properties")
candidates_dir = File(runtime_dir, "candidates")
reports_dir = File(runtime_dir, "reports")


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


def ensure_directory(path):
    if not path.isDirectory() and not path.mkdirs():
        raise IOError("Cannot create directory: " + path.getAbsolutePath())


def predict_surface(props, key, speed, distance):
    prefix = "model." + key
    gain = number(props, "model.gain")
    speed_min = number(props, prefix + ".minimum-speed")
    speed_max = number(props, prefix + ".maximum-speed")
    distance_min = number(props, prefix + ".minimum-distance.mm")
    distance_max = number(props, prefix + ".maximum-distance.mm")
    speed_bandwidth = number(props, prefix + ".speed-bandwidth")
    distance_bandwidth = number(props, prefix + ".log-distance-bandwidth")
    query_speed = max(speed_min, min(speed_max, speed))
    query_distance = max(distance_min, min(distance_max, distance))
    query_s = (query_speed - speed_min) / (speed_max - speed_min)
    query_d = math.log(query_distance / distance_min) / \
        math.log(distance_max / distance_min)
    weighted_x = 0.0
    weighted_y = 0.0
    weight_sum = 0.0
    nearest = None
    nearest_squared = float("inf")
    count = int(required(props, prefix + ".point.count"))
    for index in range(1, count + 1):
        point = prefix + ".point.{0}".format(index)
        point_speed = number(props, point + ".speed-factor")
        point_distance = number(props, point + ".distance.mm")
        point_s = (point_speed - speed_min) / (speed_max - speed_min)
        point_d = math.log(point_distance / distance_min) / \
            math.log(distance_max / distance_min)
        ds = (point_s - query_s) / speed_bandwidth
        dd = (point_d - query_d) / distance_bandwidth
        squared = ds * ds + dd * dd
        px = number(props, point + ".correction.x.mm")
        py = number(props, point + ".correction.y.mm")
        if squared < nearest_squared:
            nearest_squared = squared
            nearest = (px, py)
        if squared > 60.0:
            continue
        scatter = number(props, point + ".scatter.mm")
        floor = number(props, point + ".detection-floor.mm")
        variance = scatter * scatter + floor * floor
        weight = math.exp(-0.5 * squared) / variance
        weight_sum += weight
        weighted_x += weight * px
        weighted_y += weight * py
    if weight_sum <= 0.000000000000001:
        return gain * nearest[0], gain * nearest[1]
    return gain * weighted_x / weight_sum, gain * weighted_y / weight_sum


def residual(matrix, baseline, offset):
    hxx, hxy, hyx, hyy = matrix
    rx = baseline[0] - hxx * offset[0] - hxy * offset[1]
    ry = baseline[1] - hyx * offset[0] - hyy * offset[1]
    return rx, ry, math.hypot(rx, ry)


def solve_cell(matrix, baseline, target, maximum, safety, a_offset):
    best = (0.0, 0.0, math.hypot(baseline[0], baseline[1]),
            baseline[0], baseline[1])
    radial_step = max(0.00025, maximum / 400.0)
    radius = 0.0
    while radius <= maximum + 0.0000001:
        ring_best = None
        for index in range(720):
            angle = 2.0 * math.pi * index / 720.0
            x = radius * math.cos(angle)
            y = radius * math.sin(angle)
            if math.hypot(a_offset[0] + x, a_offset[1] + y) > \
                    safety + 0.0000001:
                continue
            rx, ry, radial = residual(matrix, baseline, (x, y))
            item = (x, y, radial, rx, ry)
            if ring_best is None or radial < ring_best[2]:
                ring_best = item
            if radial < best[2]:
                best = item
        if ring_best is not None and ring_best[2] <= target:
            return ring_best, True
        radius += radial_step
    return best, False


def rms(values):
    return math.sqrt(sum([value * value for value in values]) / len(values))


def write_properties(props, stamped, latest):
    for output in [stamped, latest]:
        stream = FileOutputStream(output)
        try:
            props.store(stream, "CoreXY dual-reference spatial candidate")
        finally:
            stream.close()


try:
    for path in [formal_file, policy_file, scan_file, response_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    formal = load_properties(formal_file)
    policy = load_properties(policy_file)
    scan = load_properties(scan_file)
    response = load_properties(response_file)
    config_hash = sha256(formal_file)
    revision = required(formal, "production.promoted-at")
    if required(scan, "state") != "completed" or \
            required(scan, "measurement.mode") != \
            "uncompensated-home-b-surface":
        raise ValueError("The latest Home B surface scan is incomplete.")
    if required(scan, "production.revision") != revision or \
            required(scan, "source-production-config.sha256").lower() != \
            config_hash.lower():
        raise ValueError("The Home B scan belongs to another production revision.")
    if required(scan, "integrity.config-unchanged") != "true":
        raise ValueError("The Home B scan failed its integrity check.")
    if required(response, "analysis.method") != "centered-four-probe-v1":
        raise ValueError("A centered four-direction Home response is required.")
    if required(response, "production.revision") != revision or \
            required(response,
                     "source-production-config.sha256").lower() != \
            config_hash.lower():
        raise ValueError("The response analysis belongs to another revision.")

    matrix = (number(response, "matrix.hxx"),
              number(response, "matrix.hxy"),
              number(response, "matrix.hyx"),
              number(response, "matrix.hyy"))
    target = number(policy, "target.radial-tolerance.mm")
    maximum_residual = target + number(
        policy, "target.effective-minimum.mm")
    safety = number(formal, "safety.max-offset.mm")
    reserve_factor = number(response, "safety.reserve-factor")
    maximum = safety * reserve_factor
    response_quality = required(response, "identification.quality")
    count = int(required(scan, "cell.count"))
    if count < 6:
        raise ValueError("At least six Home B surface cells are required.")

    head = Configuration.get().getMachine().getDefaultHead()
    primary = head.getCalibrationPrimaryFiducialLocation().convertToUnits(
        LengthUnit.Millimeters)
    primary_x = primary.getX()
    primary_y = primary.getY()
    home_x = number(scan, "reference.detected.x.mm")
    home_y = number(scan, "reference.detected.y.mm")
    anchor_separation = math.hypot(home_x - primary_x, home_y - primary_y)
    if anchor_separation < 50.0:
        raise ValueError("Primary and Home anchors are too close for spatial use.")

    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    candidate_id = "dual-reference-home-b-v1-" + stamp
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("source", "dual-reference-home-b-surface-analysis")
    props.setProperty("state", "candidate-not-runtime-enabled")
    props.setProperty("created", stamp)
    props.setProperty("candidate.id", candidate_id)
    props.setProperty("candidate.mode", "dual-anchor-diagonal-v1")
    props.setProperty("candidate.runtime-supported", "true")
    props.setProperty("candidate.minimum-agent-schema", "4")
    props.setProperty("production.revision", revision)
    props.setProperty("source-production-config", formal_file.getCanonicalPath())
    props.setProperty("source-production-config.sha256", config_hash)
    props.setProperty("source-home-scan", scan_file.getCanonicalPath())
    props.setProperty("source-home-scan.sha256", sha256(scan_file))
    props.setProperty("source-response-analysis", response_file.getCanonicalPath())
    props.setProperty("source-response-analysis.sha256", sha256(response_file))
    props.setProperty("response.analysis-id", required(response, "analysis.id"))
    props.setProperty("response.quality", response_quality)
    props.setProperty("spatial.observable-state", "B")
    props.setProperty("spatial.unobservable-state", "A")
    props.setProperty("spatial.a-policy", "retain-primary-surface")
    props.setProperty("spatial.b-policy", "interpolate-primary-to-home")
    set_float(props, "anchor.primary.x.mm", primary_x)
    set_float(props, "anchor.primary.y.mm", primary_y)
    set_float(props, "anchor.home.x.mm", home_x)
    set_float(props, "anchor.home.y.mm", home_y)
    set_float(props, "anchor.separation.mm", anchor_separation)
    set_float(props, "target.radial-tolerance.mm", target)
    set_float(props, "target.maximum-residual-guard.mm", maximum_residual)
    set_float(props, "safety.max-offset.mm", safety)
    set_float(props, "safety.home-b-reserve.mm", maximum)
    props.setProperty("home-b.point.count", str(count))

    raw_values = []
    formal_values = []
    candidate_values = []
    delta_values = []
    feasible_count = 0
    lines_points = []
    for index in range(1, count + 1):
        source = "cell.{0}".format(index)
        output = "home-b.point.{0}".format(index)
        speed = number(scan, source + ".speed-factor")
        distance = number(scan, source + ".distance.mm")
        baseline = (number(scan, source + ".residual.x.mm"),
                    number(scan, source + ".residual.y.mm"))
        cell_scatter = number(scan, source + ".scatter.mm")
        formal_a = predict_surface(formal, "a", speed, distance)
        formal_b = predict_surface(formal, "b", speed, distance)
        formal_residual = residual(matrix, baseline, formal_b)
        solution, feasible = solve_cell(
            matrix, baseline, target, maximum, safety, formal_a)
        bx, by, candidate_radial, rx, ry = solution
        delta_x = bx - formal_b[0]
        delta_y = by - formal_b[1]
        if feasible:
            feasible_count += 1
        raw_radial = math.hypot(baseline[0], baseline[1])
        delta_radial = math.hypot(delta_x, delta_y)
        raw_values.append(raw_radial)
        formal_values.append(formal_residual[2])
        candidate_values.append(candidate_radial)
        delta_values.append(delta_radial)
        set_float(props, output + ".speed-factor", speed)
        set_float(props, output + ".distance.mm", distance)
        set_float(props, output + ".scatter.mm", cell_scatter)
        set_float(props, output + ".baseline.x.mm", baseline[0])
        set_float(props, output + ".baseline.y.mm", baseline[1])
        set_float(props, output + ".baseline.mm", raw_radial)
        set_float(props, output + ".formal-b.x.mm", formal_b[0])
        set_float(props, output + ".formal-b.y.mm", formal_b[1])
        set_float(props, output + ".formal-predicted-residual.mm",
                  formal_residual[2])
        set_float(props, output + ".home-b.x.mm", bx)
        set_float(props, output + ".home-b.y.mm", by)
        set_float(props, output + ".home-b.magnitude.mm", math.hypot(bx, by))
        set_float(props, output + ".predicted-residual.x.mm", rx)
        set_float(props, output + ".predicted-residual.y.mm", ry)
        set_float(props, output + ".predicted-residual.mm", candidate_radial)
        props.setProperty(output + ".target-feasible", str(feasible).lower())
        set_float(props, output + ".spatial-delta.x.mm", delta_x)
        set_float(props, output + ".spatial-delta.y.mm", delta_y)
        set_float(props, output + ".spatial-delta.mm", delta_radial)
        lines_points.append(
            "{0:02d}: speed {1:.0%}, distance {2:.1f}; raw {3:.6f}; "
            "formal predicted {4:.6f}; Home candidate {5:.6f}; B offset "
            "({6:+.6f},{7:+.6f}); delta from formal {8:.6f} mm; target {9}".format(
                index, speed, distance, raw_radial, formal_residual[2],
                candidate_radial, bx, by, delta_radial,
                "YES" if feasible else "NO"))

    raw_rms = rms(raw_values)
    formal_rms = rms(formal_values)
    candidate_rms = rms(candidate_values)
    set_float(props, "summary.raw.rms.mm", raw_rms)
    set_float(props, "summary.formal-predicted.rms.mm", formal_rms)
    set_float(props, "summary.home-candidate-predicted.rms.mm", candidate_rms)
    set_float(props, "summary.maximum-spatial-delta.mm", max(delta_values))
    set_float(props, "summary.median-spatial-delta.mm",
              sorted(delta_values)[len(delta_values) // 2])
    props.setProperty("summary.target-feasible-count", str(feasible_count))
    props.setProperty("summary.target-feasible-total", str(count))
    meaningful = formal_rms - candidate_rms > number(
        scan, "vision.detection-floor.mm")
    props.setProperty("summary.spatial-difference-meaningful",
                      str(meaningful).lower())
    props.setProperty("integrity.config-unchanged",
                      str(sha256(formal_file) == config_hash).lower())

    ensure_directory(candidates_dir)
    ensure_directory(reports_dir)
    stamped = File(
        candidates_dir, "dual-reference-home-b-" + stamp + ".properties")
    latest = File(
        candidates_dir, "latest-dual-reference-home-b.properties")
    write_properties(props, stamped, latest)

    lines = [
        "OpenPnP CoreXY Dual-reference Home B Surface Analysis", "",
        "No movement occurred and no configuration was changed.",
        "Candidate ID: " + candidate_id,
        "Runtime support: schema 4 transient trials only",
        "Primary anchor: ({0:.3f}, {1:.3f}) mm".format(primary_x, primary_y),
        "Home anchor: ({0:.3f}, {1:.3f}) mm".format(home_x, home_y),
        "Anchor separation: {0:.3f} mm".format(anchor_separation),
        "Observable at Home: B only; A retains the Primary surface", "",
        "Response quality: " + response_quality,
        "Cells: {0}; user target feasible in {1}/{0}".format(
            count, feasible_count),
        "Raw Home B RMS: {0:.6f} mm".format(raw_rms),
        "Existing formal B predicted RMS: {0:.6f} mm".format(formal_rms),
        "Home-local candidate predicted RMS: {0:.6f} mm".format(candidate_rms),
        "Maximum spatial B difference: {0:.6f} mm".format(max(delta_values)),
        "Spatial difference above one vision-floor unit: " +
        str(meaningful).lower(), "", "Cell details:",
    ] + lines_points + ["",
        "This file describes a possible two-anchor B surface. It is limited "
        "to transient schema 4 trials and does not authorize production use."]
    report = File(
        reports_dir, "dual-reference-home-b-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
    try:
        writer.write("\r\n".join(lines))
    finally:
        writer.close()
    JOptionPane.showMessageDialog(
        None, "\n".join(lines) + "\n\nCandidate: " +
        stamped.getAbsolutePath() + "\nReport: " + report.getAbsolutePath(),
        "CoreXY Dual-reference Home B Analysis",
        JOptionPane.INFORMATION_MESSAGE if meaningful else
        JOptionPane.WARNING_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Dual-reference Home B Analysis Error",
        JOptionPane.ERROR_MESSAGE)
    raise
