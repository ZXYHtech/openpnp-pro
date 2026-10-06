import math
from jarray import zeros
from java.io import (File, FileInputStream, FileOutputStream,
                     OutputStreamWriter)
from java.security import MessageDigest
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


runtime_dir = File(Configuration.get().getScripting().getScriptsDirectory(),
                   "TornadoSMT/OpenPnP-CoreXY")
formal_file = File(runtime_dir, "config/corexy.properties")
current_file = File(runtime_dir, "sessions/current-session.properties")
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


def predict_surface(props, key, speed, distance):
    prefix = "model." + key
    gain = number(props, "model.gain")
    speed_min = number(props, prefix + ".minimum-speed")
    speed_max = number(props, prefix + ".maximum-speed")
    distance_min = number(props, prefix + ".minimum-distance.mm")
    distance_max = number(props, prefix + ".maximum-distance.mm")
    speed_bandwidth = number(props, prefix + ".speed-bandwidth")
    distance_bandwidth = number(props, prefix + ".log-distance-bandwidth")
    query_s = (max(speed_min, min(speed_max, speed)) - speed_min) / \
        (speed_max - speed_min)
    query_distance = max(distance_min, min(distance_max, distance))
    query_d = math.log(query_distance / distance_min) / \
        math.log(distance_max / distance_min)
    weighted_x = weighted_y = weight_sum = 0.0
    nearest = None
    nearest_squared = float("inf")
    count = int(required(props, prefix + ".point.count"))
    for index in range(1, count + 1):
        point = prefix + ".point.{0}".format(index)
        point_s = (number(props, point + ".speed-factor") - speed_min) / \
            (speed_max - speed_min)
        point_d = math.log(number(props, point + ".distance.mm") /
                           distance_min) / math.log(distance_max / distance_min)
        ds = (point_s - query_s) / speed_bandwidth
        dd = (point_d - query_d) / distance_bandwidth
        squared = ds * ds + dd * dd
        px = number(props, point + ".correction.x.mm")
        py = number(props, point + ".correction.y.mm")
        if squared < nearest_squared:
            nearest_squared = squared
            nearest = (px, py)
        if squared <= 60.0:
            variance = number(props, point + ".scatter.mm") ** 2 + \
                number(props, point + ".detection-floor.mm") ** 2
            weight = math.exp(-0.5 * squared) / variance
            weight_sum += weight
            weighted_x += weight * px
            weighted_y += weight * py
    if weight_sum <= 0.000000000000001:
        return gain * nearest[0], gain * nearest[1]
    return gain * weighted_x / weight_sum, gain * weighted_y / weight_sum


def fit_response(rows):
    sxx = sum([row[0][0] * row[0][0] for row in rows])
    sxy = sum([row[0][0] * row[0][1] for row in rows])
    syy = sum([row[0][1] * row[0][1] for row in rows])
    determinant = sxx * syy - sxy * sxy
    if determinant <= 0.0000000001:
        raise ValueError("Fresh formal B offsets do not identify a 2D response.")
    result = []
    for output in [0, 1]:
        tx = sum([row[1][output] * row[0][0] for row in rows])
        ty = sum([row[1][output] * row[0][1] for row in rows])
        result.extend([(tx * syy - ty * sxy) / determinant,
                       (ty * sxx - tx * sxy) / determinant])
    return tuple(result)


def predicted(matrix, baseline, delta):
    return (baseline[0] - matrix[0] * delta[0] - matrix[1] * delta[1],
            baseline[1] - matrix[2] * delta[0] - matrix[3] * delta[1])


def solve_minimum_delta(matrix, baseline, formal_a, formal_b, target, safety):
    best = (0.0, 0.0, math.hypot(baseline[0], baseline[1]))
    radial_step = max(0.00025, safety / 400.0)
    radius = 0.0
    while radius <= safety + 0.0000001:
        ring_best = None
        for angle_index in range(720):
            angle = 2.0 * math.pi * angle_index / 720.0
            dx = radius * math.cos(angle)
            dy = radius * math.sin(angle)
            if math.hypot(formal_a[0] + formal_b[0] + dx,
                          formal_a[1] + formal_b[1] + dy) > safety + 0.0000001:
                continue
            residual = predicted(matrix, baseline, (dx, dy))
            radial = math.hypot(residual[0], residual[1])
            item = (dx, dy, radial)
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


def write_properties(props, path):
    stream = FileOutputStream(path)
    try:
        props.store(stream, "CoreXY fresh residual dual-reference candidate")
    finally:
        stream.close()


try:
    if not current_file.isFile() or not formal_file.isFile():
        raise IOError("Fresh session or formal configuration is missing.")
    session = load_properties(current_file)
    session_dir = File(required(session, "session.directory"))
    formal_home_file = File(session_dir, "formal-home-b.properties")
    uncomp_home_file = File(session_dir, "uncompensated-home-b.properties")
    formal_primary_file = File(session_dir, "formal-primary.properties")
    for path in [formal_home_file, uncomp_home_file, formal_primary_file]:
        if not path.isFile():
            raise IOError("Fresh session measurement is missing: " +
                          path.getName())
    formal = load_properties(formal_file)
    formal_home = load_properties(formal_home_file)
    uncomp_home = load_properties(uncomp_home_file)
    config_hash = sha256(formal_file)
    session_id = required(session, "session.id")
    if required(session, "formal.sha256").lower() != config_hash.lower():
        raise ValueError("Formal configuration changed after fresh measurement.")
    for measured in [formal_home, uncomp_home]:
        if required(measured, "state") != "completed" or \
                required(measured, "session.id") != session_id:
            raise ValueError("A Home measurement is incomplete or from another session.")
    count = int(required(formal_home, "cell.count"))
    if count != int(required(uncomp_home, "cell.count")) or count < 6:
        raise ValueError("Fresh Home grids do not match.")

    rows = []
    cells = []
    for index in range(1, count + 1):
        key = "cell.{0}".format(index)
        speed = number(formal_home, key + ".speed-factor")
        distance = number(formal_home, key + ".distance.mm")
        if abs(speed - number(uncomp_home, key + ".speed-factor")) > 0.000001 or \
                abs(distance - number(uncomp_home, key + ".distance.mm")) > 0.000001:
            raise ValueError("Fresh Home cell grids do not match.")
        formal_residual = (number(formal_home, key + ".residual.x.mm"),
                           number(formal_home, key + ".residual.y.mm"))
        uncomp_residual = (number(uncomp_home, key + ".residual.x.mm"),
                           number(uncomp_home, key + ".residual.y.mm"))
        formal_b = predict_surface(formal, "b", speed, distance)
        observed_removal = (uncomp_residual[0] - formal_residual[0],
                            uncomp_residual[1] - formal_residual[1])
        rows.append((formal_b, observed_removal))
        cells.append((speed, distance, formal_residual, formal_b,
                      max(number(formal_home, key + ".scatter.mm"),
                          number(uncomp_home, key + ".scatter.mm"))))
    matrix = fit_response(rows)
    fit_errors = []
    for formal_b, removal in rows:
        estimate = (matrix[0] * formal_b[0] + matrix[1] * formal_b[1],
                    matrix[2] * formal_b[0] + matrix[3] * formal_b[1])
        fit_errors.append(math.hypot(removal[0] - estimate[0],
                                     removal[1] - estimate[1]))
    fit_rms = rms(fit_errors)
    vision_floor = max(number(formal_home, "vision.detection-floor.mm"),
                       number(uncomp_home, "vision.detection-floor.mm"),
                       number(formal_home, "vision.stationary-noise.mm"),
                       number(uncomp_home, "vision.stationary-noise.mm"))
    if fit_rms > vision_floor * 2.0:
        raise ValueError("Fresh response fit exceeds two vision-noise units.")

    target = number(session, "target.radial-tolerance.mm")
    safety = number(formal, "safety.max-offset.mm")
    primary_maximum_distance = 0.0
    primary_scan = load_properties(formal_primary_file)
    for stage in range(1, int(required(primary_scan, "stage.count")) + 1):
        prefix = "stage.{0}.a".format(stage)
        for point in range(1, int(required(
                primary_scan, prefix + ".distance.count")) + 1):
            primary_maximum_distance = max(primary_maximum_distance, number(
                primary_scan, prefix + ".distance.{0}.virtual-distance.mm".format(point)))
    primary_x = number(session, "primary.x.mm")
    primary_y = number(session, "primary.y.mm")
    home_x = number(session, "home.x.mm")
    home_y = number(session, "home.y.mm")
    separation = math.hypot(home_x - primary_x, home_y - primary_y)
    protection_radius = min(separation * 0.75,
                            primary_maximum_distance * 0.5 + 15.0)

    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    candidate_id = "fresh-residual-home-b-v1-" + stamp
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("source", "dual-reference-home-b-surface-analysis")
    props.setProperty("state", "candidate-not-runtime-enabled")
    props.setProperty("created", stamp)
    props.setProperty("candidate.id", candidate_id)
    props.setProperty("candidate.mode", "dual-anchor-diagonal-v1")
    props.setProperty("candidate.fit", "fresh-same-time-minimum-residual-v1")
    props.setProperty("session.id", session_id)
    props.setProperty("production.revision",
                      required(formal, "production.promoted-at"))
    props.setProperty("source-production-config", formal_file.getCanonicalPath())
    props.setProperty("source-production-config.sha256", config_hash)
    props.setProperty("source-home-scan", uncomp_home_file.getCanonicalPath())
    props.setProperty("source-home-scan.sha256", sha256(uncomp_home_file))
    props.setProperty("source-session-formal-primary",
                      formal_primary_file.getCanonicalPath())
    props.setProperty("source-session-formal-primary.sha256",
                      sha256(formal_primary_file))
    set_float(props, "anchor.primary.x.mm", primary_x)
    set_float(props, "anchor.primary.y.mm", primary_y)
    set_float(props, "anchor.home.x.mm", home_x)
    set_float(props, "anchor.home.y.mm", home_y)
    set_float(props, "primary.protection-radius.mm", protection_radius)
    set_float(props, "target.radial-tolerance.mm", target)
    set_float(props, "target.maximum-residual-guard.mm",
              target + vision_floor * 3.0)
    set_float(props, "response.hxx", matrix[0])
    set_float(props, "response.hxy", matrix[1])
    set_float(props, "response.hyx", matrix[2])
    set_float(props, "response.hyy", matrix[3])
    set_float(props, "response.fit-rms.mm", fit_rms)
    props.setProperty("home-b.point.count", str(count))

    before_values = []
    predicted_values = []
    feasible_count = 0
    max_delta = 0.0
    details = []
    for index, cell in enumerate(cells):
        speed, distance, baseline, formal_b, cell_scatter = cell
        formal_a = predict_surface(formal, "a", speed, distance)
        solution, feasible = solve_minimum_delta(
            matrix, baseline, formal_a, formal_b, target, safety)
        dx, dy, residual = solution
        home_b = (formal_b[0] + dx, formal_b[1] + dy)
        prefix = "home-b.point.{0}".format(index + 1)
        set_float(props, prefix + ".speed-factor", speed)
        set_float(props, prefix + ".distance.mm", distance)
        set_float(props, prefix + ".home-b.x.mm", home_b[0])
        set_float(props, prefix + ".home-b.y.mm", home_b[1])
        set_float(props, prefix + ".scatter.mm", cell_scatter)
        set_float(props, prefix + ".spatial-delta.x.mm", dx)
        set_float(props, prefix + ".spatial-delta.y.mm", dy)
        set_float(props, prefix + ".predicted-residual.mm", residual)
        props.setProperty(prefix + ".target-feasible", str(feasible).lower())
        before_values.append(math.hypot(baseline[0], baseline[1]))
        predicted_values.append(residual)
        max_delta = max(max_delta, math.hypot(dx, dy))
        if feasible:
            feasible_count += 1
        details.append(
            "{0:02d}: speed {1:.0%}, distance {2:.1f}; formal {3:.6f}; "
            "predicted {4:.6f}; additional B {5:.6f} mm; target {6}".format(
                index + 1, speed, distance, before_values[-1], residual,
                math.hypot(dx, dy), "YES" if feasible else "NO"))
    before_rms = rms(before_values)
    predicted_rms = rms(predicted_values)
    set_float(props, "summary.formal-measured.rms.mm", before_rms)
    set_float(props, "summary.home-candidate-predicted.rms.mm", predicted_rms)
    set_float(props, "summary.maximum-spatial-delta.mm", max_delta)
    props.setProperty("summary.target-feasible-count", str(feasible_count))
    props.setProperty("summary.target-feasible-total", str(count))
    props.setProperty("summary.spatial-difference-meaningful",
                      str(before_rms - predicted_rms > vision_floor).lower())
    props.setProperty("integrity.config-unchanged",
                      str(sha256(formal_file) == config_hash).lower())

    if not candidates_dir.isDirectory() and not candidates_dir.mkdirs():
        raise IOError("Cannot create candidates directory.")
    if not reports_dir.isDirectory() and not reports_dir.mkdirs():
        raise IOError("Cannot create reports directory.")
    stamped = File(candidates_dir, "fresh-residual-home-b-" + stamp +
                   ".properties")
    latest = File(candidates_dir, "latest-dual-reference-home-b.properties")
    write_properties(props, stamped)
    write_properties(props, latest)
    lines = [
        "OpenPnP CoreXY Fresh Residual Candidate Fit", "",
        "No movement occurred and no configuration was changed.",
        "Candidate: " + candidate_id,
        "Session: " + session_id,
        "Response matrix: [{0:+.4f} {1:+.4f}; {2:+.4f} {3:+.4f}]".format(
            matrix[0], matrix[1], matrix[2], matrix[3]),
        "Response fit RMS: {0:.6f} mm; vision unit: {1:.6f} mm".format(
            fit_rms, vision_floor),
        "Primary protection radius: {0:.1f} mm".format(protection_radius),
        "Home measured RMS: {0:.6f} mm".format(before_rms),
        "Home predicted RMS: {0:.6f} mm".format(predicted_rms),
        "Target feasible: {0}/{1}; maximum additional B: {2:.6f} mm".format(
            feasible_count, count, max_delta), "", "Cell details:"
    ] + details + ["",
        "This is an analysis-only transient candidate. Physical Home and "
        "Primary validation remain mandatory."]
    report = File(reports_dir, "fresh-residual-home-b-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
    try:
        writer.write("\r\n".join(lines))
    finally:
        writer.close()
    JOptionPane.showMessageDialog(
        None, "\n".join(lines) + "\n\nCandidate: " +
        stamped.getAbsolutePath() + "\nReport: " + report.getAbsolutePath(),
        "CoreXY Fresh Residual Fit",
        JOptionPane.INFORMATION_MESSAGE if feasible_count == count else
        JOptionPane.WARNING_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(None, str(error),
                                  "CoreXY Fresh Residual Fit Error",
                                  JOptionPane.ERROR_MESSAGE)
    raise
