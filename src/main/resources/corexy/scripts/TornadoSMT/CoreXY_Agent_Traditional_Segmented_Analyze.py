import math
from jarray import zeros
from java.io import File, FileInputStream, FileOutputStream, OutputStreamWriter
from java.security import MessageDigest
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
session_file = File(runtime_dir, "sessions/current-session.properties")
formal_file = File(runtime_dir, "config/corexy.properties")
candidates_dir = File(runtime_dir, "candidates")
reports_dir = File(runtime_dir, "reports")
validation_file = File(
    runtime_dir,
    "diagnostics/latest-traditional-segmented-physical-validation.properties")


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


def median(values):
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return 0.5 * (ordered[middle - 1] + ordered[middle])


def ensure_directory(path):
    if not path.isDirectory() and not path.mkdirs():
        raise IOError("Cannot create directory: " + path.getAbsolutePath())


def predict_model(props, key, speed, distance):
    prefix = "model." + key
    minimum_speed = float(required(props, prefix + ".minimum-speed"))
    maximum_speed = float(required(props, prefix + ".maximum-speed"))
    minimum_distance = float(required(props, prefix + ".minimum-distance.mm"))
    maximum_distance = float(required(props, prefix + ".maximum-distance.mm"))
    speed_bandwidth = float(required(props, prefix + ".speed-bandwidth"))
    distance_bandwidth = float(required(
        props, prefix + ".log-distance-bandwidth"))
    query_speed = max(minimum_speed, min(maximum_speed, speed))
    query_distance = max(minimum_distance, min(maximum_distance, distance))
    query_s = ((query_speed - minimum_speed) /
               (maximum_speed - minimum_speed))
    query_d = (math.log(query_distance / minimum_distance) /
               math.log(maximum_distance / minimum_distance))
    sum_weight = 0.0
    sum_x = 0.0
    sum_y = 0.0
    count = int(required(props, prefix + ".point.count"))
    for index in range(1, count + 1):
        point = prefix + ".point.{0}".format(index)
        point_speed = float(required(props, point + ".speed-factor"))
        point_distance = float(required(props, point + ".distance.mm"))
        point_s = ((point_speed - minimum_speed) /
                   (maximum_speed - minimum_speed))
        point_d = (math.log(point_distance / minimum_distance) /
                   math.log(maximum_distance / minimum_distance))
        ds = (point_s - query_s) / speed_bandwidth
        dd = (point_d - query_d) / distance_bandwidth
        squared = ds * ds + dd * dd
        if squared > 60.0:
            continue
        scatter = float(required(props, point + ".scatter.mm"))
        point_floor = float(required(props, point + ".detection-floor.mm"))
        weight = math.exp(-0.5 * squared) / (
            scatter * scatter + point_floor * point_floor)
        sum_weight += weight
        sum_x += weight * float(required(
            props, point + ".correction.x.mm"))
        sum_y += weight * float(required(
            props, point + ".correction.y.mm"))
    if sum_weight <= 1e-15:
        raise ValueError("The previous segmented model cannot predict this cell.")
    return sum_x / sum_weight, sum_y / sum_weight


def write_properties(props, stamped, latest):
    for path in [stamped, latest]:
        stream = FileOutputStream(path)
        try:
            props.store(stream, "Machine-local traditional segmented CoreXY candidate")
        finally:
            stream.close()


try:
    for path in [session_file, formal_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    session = load_properties(session_file)
    formal = load_properties(formal_file)
    session_dir = File(required(session, "session.directory"))
    baseline_file = File(session_dir, "uncompensated-primary.properties")
    if not baseline_file.isFile():
        raise IOError("Fresh uncompensated Primary baseline is missing.")
    baseline = load_properties(baseline_file)
    if required(baseline, "state") != "completed" or \
            required(baseline, "measurement.mode") != "uncompensated-primary" or \
            required(baseline, "compensation.applied") != "false":
        raise ValueError("The same-session uncompensated Primary baseline is invalid.")
    if sha256(formal_file).lower() != required(session, "formal.sha256").lower():
        raise ValueError("The formal CoreXY configuration changed after this session began.")

    stage_count = int(required(baseline, "stage.count"))
    if stage_count < 2:
        raise ValueError("At least two speed stages are required.")
    speeds = []
    direction_rows = {"a": {}, "b": {}}
    floor_values = []
    for stage_index in range(1, stage_count + 1):
        stage = "stage.{0}".format(stage_index)
        speed = float(required(baseline, stage + ".speed-factor"))
        speeds.append(speed)
        floor_values.append(float(required(
            baseline, stage + ".vision-detection-floor.mm")))
        for direction in ["a", "b"]:
            count = int(required(
                baseline, stage + "." + direction + ".distance.count"))
            for distance_index in range(1, count + 1):
                prefix = stage + "." + direction + ".distance.{0}".format(
                    distance_index)
                distance = float(required(
                    baseline, prefix + ".virtual-distance.mm"))
                direction_rows[direction].setdefault(distance, []).append({
                    "speed": speed,
                    "x": float(required(baseline, prefix + ".correction.x.mm")),
                    "y": float(required(baseline, prefix + ".correction.y.mm")),
                    "scatter": float(required(baseline, prefix + ".scatter.mm"))
                })
    distances = sorted(direction_rows["b"].keys())
    if len(distances) < 3:
        raise ValueError("At least three distance bands are required.")
    floor = max(floor_values)
    gain = 0.25
    a_rms = float(required(baseline, "current.a.rms.mm"))
    a_disable_threshold = 3.5 * floor
    a_disabled = a_rms <= a_disable_threshold
    bands_by_direction = {}
    for direction in ["a", "b"]:
        bands = []
        for distance in distances:
            rows = direction_rows[direction][distance]
            x = median([row["x"] for row in rows])
            y = median([row["y"] for row in rows])
            scatter = max(floor, median([row["scatter"] for row in rows]))
            if direction == "a" and a_disabled:
                x, y = 0.0, 0.0
            bands.append((distance, x, y, scatter))
        bands_by_direction[direction] = bands
    grid_by_direction = {"a": {}, "b": {}}
    for direction in ["a", "b"]:
        for speed in speeds:
            for distance in distances:
                matching = [row for row in direction_rows[direction][distance]
                            if abs(row["speed"] - speed) < 1e-9]
                if not matching:
                    raise ValueError("A speed/distance baseline cell is missing.")
                x = median([row["x"] for row in matching])
                y = median([row["y"] for row in matching])
                scatter = max(
                    floor, median([row["scatter"] for row in matching]))
                if direction == "a" and a_disabled:
                    x, y = 0.0, 0.0
                grid_by_direction[direction][(speed, distance)] = (
                    x, y, scatter)

    refined = False
    refinement_source = None
    if validation_file.isFile():
        validation = load_properties(validation_file)
        previous_trial_file = File(validation.getProperty("source-trial", ""))
        if (validation.getProperty("state", "") in
                ["passed", "failed-acceptance"] and
                previous_trial_file.isFile() and
                File(validation.getProperty("source-baseline", "")).getCanonicalPath() ==
                baseline_file.getCanonicalPath()):
            previous_trial = load_properties(previous_trial_file)
            if previous_trial.getProperty("strategy.kind", "") == \
                    "TRADITIONAL_DISTANCE_BANDS_V1":
                refined = True
                refinement_source = validation_file
                for stage_index in range(1, stage_count + 1):
                    stage = "stage.{0}".format(stage_index)
                    speed = float(required(baseline, stage + ".speed-factor"))
                    count = int(required(
                        baseline, stage + ".b.distance.count"))
                    for distance_index in range(1, count + 1):
                        prefix = stage + ".b.distance.{0}".format(distance_index)
                        distance = float(required(
                            baseline, prefix + ".virtual-distance.mm"))
                        before = (
                            float(required(baseline, prefix + ".correction.x.mm")),
                            float(required(baseline, prefix + ".correction.y.mm")))
                        after = (
                            float(required(validation, prefix + ".correction.x.mm")),
                            float(required(validation, prefix + ".correction.y.mm")))
                        before_magnitude = math.hypot(before[0], before[1])
                        after_magnitude = math.hypot(after[0], after[1])
                        delta = (after[0] - before[0], after[1] - before[1])
                        denominator = delta[0] * delta[0] + delta[1] * delta[1]
                        scale = 0.0
                        if before_magnitude - after_magnitude >= floor and \
                                denominator > 1e-15:
                            optimum = -(before[0] * delta[0] +
                                        before[1] * delta[1]) / denominator
                            scale = 0.85 * max(0.0, min(1.5, optimum))
                        old_x, old_y = predict_model(
                            previous_trial, "b", speed, distance)
                        x = scale * old_x
                        y = scale * old_y
                        applied = gain * math.hypot(x, y)
                        if applied > 0.0675:
                            limit_scale = 0.0675 / applied
                            x *= limit_scale
                            y *= limit_scale
                        grid_by_direction["b"][(speed, distance)] = (
                            x, y, floor)
    maximum_offset = 0.0
    for speed in speeds:
        for distance in distances:
            a_point = grid_by_direction["a"][(speed, distance)]
            b_point = grid_by_direction["b"][(speed, distance)]
            maximum_offset = max(maximum_offset, gain * math.hypot(
                a_point[0] + b_point[0], a_point[1] + b_point[1]))
    safety_limit = float(required(formal, "safety.max-offset.mm"))
    if maximum_offset > safety_limit + 1e-9:
        raise ValueError("The 25% segmented candidate exceeds the formal safety limit.")

    props = Properties()
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    props.setProperty("schema.version", "3")
    props.setProperty("source", "traditional-segmented-ab-v1")
    props.setProperty("state", "fitted-not-applied")
    props.setProperty("created", stamp)
    # The Agent runtime uses the Gaussian-kernel interpolator for every
    # schema-3 surface. The strategy metadata below distinguishes how the
    # points were produced; model.kind must name the runtime interpolator.
    props.setProperty("model.kind", "GAUSSIAN_KERNEL_LOG_DISTANCE_V1")
    props.setProperty("strategy.kind", "TRADITIONAL_DISTANCE_BANDS_V1")
    props.setProperty(
        "model.policy",
        ("A_DISABLED_B_EMPIRICAL_CELL_REFINEMENT" if refined and a_disabled else
         "A_AND_B_EMPIRICAL_CELL_REFINEMENT" if refined else
         "A_DISABLED_BELOW_NOISE_B_SPEED_DISTANCE_GRID" if a_disabled else
         "A_AND_B_SPEED_DISTANCE_GRID"))
    props.setProperty("strategy.revision", "2" if refined else "1")
    if refined:
        props.setProperty(
            "source-physical-validation", refinement_source.getCanonicalPath())
        props.setProperty(
            "source-physical-validation.sha256", sha256(refinement_source))
    props.setProperty("model.a.disabled", str(a_disabled).lower())
    set_float(props, "model.a.disable-threshold.mm", a_disable_threshold)
    props.setProperty("machine.scope", "local-only")
    props.setProperty("machine.axis.x.id", required(formal, "axis.x.id"))
    props.setProperty("machine.axis.y.id", required(formal, "axis.y.id"))
    props.setProperty("source-session", required(session, "session.id"))
    props.setProperty("source-baseline", baseline_file.getCanonicalPath())
    props.setProperty("source-baseline.sha256", sha256(baseline_file))
    props.setProperty("source-formal", formal_file.getCanonicalPath())
    props.setProperty("source-formal.sha256", sha256(formal_file))
    props.setProperty("candidate.gain", "0.250000000")
    set_float(props, "candidate.maximum-offset.mm", maximum_offset)
    set_float(props, "safety.maximum-offset.mm", safety_limit)
    props.setProperty("target.radial-tolerance.mm",
                      required(session, "target.radial-tolerance.mm"))
    set_float(props, "target.minimum-measurable.mm", floor)
    set_float(props, "target.maximum-residual-guard.mm",
              float(required(session, "target.radial-tolerance.mm")) + 3.0 * floor)

    for key, direction in [("a", "A"), ("b", "B")]:
        prefix = "model." + key
        props.setProperty(prefix + ".direction", direction)
        props.setProperty(prefix + ".speed-bandwidth", "0.050000000")
        props.setProperty(prefix + ".log-distance-bandwidth", "0.020000000")
        set_float(props, prefix + ".minimum-speed", min(speeds))
        set_float(props, prefix + ".maximum-speed", max(speeds))
        set_float(props, prefix + ".minimum-distance.mm", min(distances))
        set_float(props, prefix + ".maximum-distance.mm", max(distances))
        props.setProperty(prefix + ".point.count",
                          str(len(speeds) * len(distances)))
        point_index = 0
        for speed in speeds:
            for distance in distances:
                bx, by, scatter = grid_by_direction[key][(speed, distance)]
                point_index += 1
                point = prefix + ".point.{0}".format(point_index)
                set_float(props, point + ".speed-factor", speed)
                set_float(props, point + ".distance.mm", distance)
                set_float(props, point + ".correction.x.mm", bx)
                set_float(props, point + ".correction.y.mm", by)
                set_float(props, point + ".scatter.mm", scatter)
                set_float(props, point + ".detection-floor.mm", floor)

    ensure_directory(candidates_dir)
    ensure_directory(reports_dir)
    stamped = File(candidates_dir, "traditional-segmented-" + stamp + ".properties")
    latest = File(candidates_dir, "latest-traditional-segmented.properties")
    write_properties(props, stamped, latest)
    report = File(reports_dir, "traditional-segmented-" + stamp + ".txt")
    lines = [
        "OpenPnP CoreXY Traditional Segmented A/B Candidate",
        "",
        "State: fitted-not-applied",
        "Machine scope: local-only",
        "Source session: " + required(session, "session.id"),
        "Source baseline SHA-256: " + sha256(baseline_file),
        "Source formal SHA-256: " + sha256(formal_file),
        "Strategy revision: " + ("2 (empirical cell refinement)" if refined
                                  else "1 (initial speed/distance grid)"),
        ("A policy: disabled; RMS {0:.6f} mm <= noise threshold {1:.6f} mm".format(
            a_rms, a_disable_threshold) if a_disabled else
         "A policy: speed/distance grid; RMS {0:.6f} mm > noise threshold "
         "{1:.6f} mm".format(a_rms, a_disable_threshold)),
        ("B policy: speed/distance cells retained only after measured improvement"
         if refined else "B policy: direct speed/distance grid"),
        "Gain: 25%; maximum applied offset: {0:.6f} mm".format(maximum_offset),
        "",
        "B speed/distance cells:"
    ]
    for speed in speeds:
        for distance in distances:
            x, y, scatter = grid_by_direction["b"][(speed, distance)]
            if refined:
                lines.append("{0:.0%} / {1:.1f} mm: 25% applied "
                             "({2:+.6f}, {3:+.6f}) mm; {4}".format(
                                 speed, distance, gain * x, gain * y,
                                 "ACTIVE" if math.hypot(x, y) > 1e-12
                                 else "DISABLED"))
            else:
                lines.append("{0:.0%} / {1:.1f} mm: full vector "
                             "({2:+.6f}, {3:+.6f}) mm; "
                             "25% applied ({4:+.6f}, {5:+.6f}) mm; "
                             "scatter {6:.6f} mm".format(
                                 speed, distance, x, y,
                                 gain * x, gain * y, scatter))
    lines.extend(["", "No movement occurred and no configuration was changed."])
    writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
    try:
        writer.write("\r\n".join(lines))
    finally:
        writer.close()
    JOptionPane.showMessageDialog(
        None, "\n".join(lines) + "\n\nCandidate: " + stamped.getAbsolutePath() +
        "\nReport: " + report.getAbsolutePath(),
        "CoreXY Traditional Segmented Analysis", JOptionPane.INFORMATION_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(None, str(error),
                                  "CoreXY Traditional Segmented Analysis Error",
                                  JOptionPane.ERROR_MESSAGE)
    raise
