import math
import sys
from jarray import zeros
from java.io import (File, FileInputStream, FileOutputStream,
                     OutputStreamWriter)
from java.security import MessageDigest
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


CANDIDATE_GAINS = [
    0.25, 0.26, 0.27, 0.28, 0.29, 0.30, 0.31, 0.32, 0.35, 0.40, 0.50]

scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
config_file = File(runtime_dir, "config/corexy.properties")
policy_file = File(runtime_dir, "config/calibration-policy.properties")
health_file = File(runtime_dir, "diagnostics/latest-production-health.properties")
confirmation_file = File(
    runtime_dir, "diagnostics/latest-production-health-confirmation.properties")
diagnostics_dir = File(runtime_dir, "diagnostics")
reports_dir = File(runtime_dir, "reports")


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


def write_properties(props, stamped, latest):
    for output in [stamped, latest]:
        stream = FileOutputStream(output)
        try:
            props.store(stream, "OpenPnP CoreXY health warning candidate analysis")
        finally:
            stream.close()


def model_points(config, direction):
    prefix = "model." + direction.lower()
    result = []
    count = int(required(config, prefix + ".point.count"))
    for index in range(1, count + 1):
        point = prefix + ".point.{0}".format(index)
        result.append({
            "speed": number(config, point + ".speed-factor"),
            "distance": number(config, point + ".distance.mm"),
            "x": number(config, point + ".correction.x.mm"),
            "y": number(config, point + ".correction.y.mm"),
            "scatter": number(config, point + ".scatter.mm"),
            "floor": number(config, point + ".detection-floor.mm")
        })
    return result


def predict(config, points, direction, speed, distance):
    prefix = "model." + direction.lower()
    minimum_speed = number(config, prefix + ".minimum-speed")
    maximum_speed = number(config, prefix + ".maximum-speed")
    minimum_distance = number(config, prefix + ".minimum-distance.mm")
    maximum_distance = number(config, prefix + ".maximum-distance.mm")
    speed_bandwidth = number(config, prefix + ".speed-bandwidth")
    distance_bandwidth = number(
        config, prefix + ".log-distance-bandwidth")
    query_speed = max(minimum_speed, min(maximum_speed, speed))
    query_distance = max(minimum_distance, min(maximum_distance, distance))
    query_s = ((query_speed - minimum_speed) /
               (maximum_speed - minimum_speed))
    query_d = (math.log(query_distance / minimum_distance) /
               math.log(maximum_distance / minimum_distance))
    sum_weight = 0.0
    sum_x = 0.0
    sum_y = 0.0
    nearest = None
    for point in points:
        point_s = ((point["speed"] - minimum_speed) /
                   (maximum_speed - minimum_speed))
        point_d = (math.log(point["distance"] / minimum_distance) /
                   math.log(maximum_distance / minimum_distance))
        ds = (point_s - query_s) / speed_bandwidth
        dd = (point_d - query_d) / distance_bandwidth
        squared = ds * ds + dd * dd
        if nearest is None or squared < nearest[0]:
            nearest = (squared, point["x"], point["y"])
        if squared > 60.0:
            continue
        variance = point["scatter"] ** 2 + point["floor"] ** 2
        weight = math.exp(-0.5 * squared) / variance
        sum_weight += weight
        sum_x += weight * point["x"]
        sum_y += weight * point["y"]
    if sum_weight <= 1e-15:
        return nearest[1], nearest[2]
    return sum_x / sum_weight, sum_y / sum_weight


def heldout_observations(props):
    observations = []
    stage_count = int(required(props, "stage.count"))
    floors = []
    for stage_index in range(1, stage_count + 1):
        stage = "stage.{0}".format(stage_index)
        speed = number(props, stage + ".speed-factor")
        floors.append(number(props, stage + ".vision-detection-floor.mm"))
        for direction in ["A", "B"]:
            prefix = stage + "." + direction.lower() + ".distance"
            count = int(required(props, prefix + ".count"))
            for distance_index in range(1, count + 1):
                point = prefix + ".{0}".format(distance_index)
                observations.append({
                    "direction": direction,
                    "speed": speed,
                    "distance": number(
                        props, point + ".virtual-distance.mm"),
                    "x": number(props, point + ".correction.x.mm"),
                    "y": number(props, point + ".correction.y.mm")
                })
    return observations, max(floors)


def summarize(values, baseline_values):
    ordered = sorted(values)
    rms = math.sqrt(sum([value * value for value in ordered]) / len(ordered))
    p95 = ordered[max(0, int(math.ceil(len(ordered) * 0.95)) - 1)]
    worsening = max([
        values[index] - baseline_values[index]
        for index in range(len(values))])
    return {
        "rms": rms,
        "p95": p95,
        "maximum": ordered[-1],
        "maximum-worsening": worsening
    }


def evaluate(config, points, observations, gain, selected_direction=None):
    residuals = []
    baseline = []
    for observation in observations:
        if selected_direction is not None and \
                observation["direction"] != selected_direction:
            continue
        estimate = predict(
            config, points[observation["direction"]],
            observation["direction"], observation["speed"],
            observation["distance"])
        baseline.append(math.hypot(observation["x"], observation["y"]))
        residuals.append(math.hypot(
            observation["x"] - gain * estimate[0],
            observation["y"] - gain * estimate[1]))
    return summarize(residuals, baseline), summarize(baseline, baseline)


def maximum_combined_offset(points, gain):
    maximum = 0.0
    for a in points["A"] + [{"x": 0.0, "y": 0.0}]:
        for b in points["B"] + [{"x": 0.0, "y": 0.0}]:
            maximum = max(maximum, gain * math.hypot(
                a["x"] + b["x"], a["y"] + b["y"]))
    return maximum


def metric_line(label, metrics):
    return ("{0}: RMS {1:.6f}, P95 {2:.6f}, maximum {3:.6f}, "
            "worst worsening {4:.6f} mm").format(
        label, metrics["rms"], metrics["p95"], metrics["maximum"],
        metrics["maximum-worsening"])


def analyze():
    for path in [config_file, policy_file, health_file, confirmation_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    config_hash_before = sha256(config_file)
    config = load_properties(config_file)
    policy = load_properties(policy_file)
    health = load_properties(health_file)
    confirmation = load_properties(confirmation_file)
    if required(config, "production.persistence") != "persistent":
        raise ValueError("The formal configuration is not persistent production.")
    if required(health, "health.result") != "WARNING":
        raise ValueError("The latest production health result is not WARNING.")
    if required(confirmation, "confirmation.result") != "WARNING_CONFIRMED":
        raise ValueError("The latest warning was not confirmed.")
    promoted_at = required(config, "production.promoted-at")
    if required(health, "production.promoted-at") != promoted_at or \
            required(confirmation, "production.promoted-at") != promoted_at:
        raise ValueError("Health evidence belongs to another production revision.")

    physical_reference = load_properties(File(required(
        config, "production.source-physical-validation")))
    heldout_file = File(required(physical_reference, "source-baseline"))
    if not heldout_file.isFile():
        raise IOError("Held-out evidence not found: " +
                      heldout_file.getAbsolutePath())
    heldout = load_properties(heldout_file)
    observations, observed_floor = heldout_observations(heldout)
    points = {
        "A": model_points(config, "A"),
        "B": model_points(config, "B")
    }
    all_baseline = evaluate(
        config, points, observations, 0.0)[1]
    a_baseline = evaluate(
        config, points, observations, 0.0, "A")[1]
    b_baseline = evaluate(
        config, points, observations, 0.0, "B")[1]
    local_limit = max(0.010, observed_floor * 2.0)
    safety_limit = number(config, "safety.max-offset.mm")
    floor_multiple = number(
        policy, "target.minimum-vision-floor-multiple")
    minimum_target = observed_floor * floor_multiple
    user_target = number(policy, "target.radial-tolerance.mm")
    if user_target < minimum_target:
        raise ValueError(
            "The user radial target is below the measurable minimum: "
            "{0:.6f} mm.".format(minimum_target))

    direction = required(confirmation, "warning.direction").upper()
    speed = number(confirmation, "warning.speed-factor")
    distance = number(confirmation, "warning.distance.mm")
    reference_magnitude = number(
        confirmation, "warning.reference.magnitude.mm")
    residual_x = number(confirmation, "target.correction.x.mm")
    residual_y = number(confirmation, "target.correction.y.mm")
    current_gain = number(config, "model.gain")
    warning_model = predict(
        config, points[direction], direction, speed, distance)
    estimated_x = residual_x + current_gain * warning_model[0]
    estimated_y = residual_y + current_gain * warning_model[1]
    health_pass_guard = number(confirmation, "confirmation.pass-guard.mm")
    legacy_health_limit = reference_magnitude + health_pass_guard
    health_target_limit = user_target
    robust_target_limit = max(
        minimum_target, user_target - observed_floor * 0.5)
    safety_reserve_limit = safety_limit * 0.95

    candidates = []
    for gain in CANDIDATE_GAINS:
        all_metrics = evaluate(config, points, observations, gain)[0]
        a_metrics = evaluate(config, points, observations, gain, "A")[0]
        b_metrics = evaluate(config, points, observations, gain, "B")[0]
        heldout_passed = (
            all_metrics["rms"] <= all_baseline["rms"] * 0.90 and
            all_metrics["p95"] <= all_baseline["p95"] and
            all_metrics["maximum"] <= all_baseline["maximum"] and
            all_metrics["maximum-worsening"] <= local_limit and
            a_metrics["rms"] <= a_baseline["rms"] * 1.05 and
            b_metrics["rms"] <= b_baseline["rms"] * 0.90)
        maximum_offset = maximum_combined_offset(points, gain)
        safety_passed = maximum_offset <= safety_limit
        safety_reserve_passed = maximum_offset <= safety_reserve_limit
        target_x = estimated_x - gain * warning_model[0]
        target_y = estimated_y - gain * warning_model[1]
        target_residual = math.hypot(target_x, target_y)
        warning_cleared = target_residual <= health_target_limit
        robustly_cleared = target_residual <= robust_target_limit
        candidates.append({
            "gain": gain,
            "all": all_metrics,
            "a": a_metrics,
            "b": b_metrics,
            "maximum-offset": maximum_offset,
            "heldout-passed": heldout_passed,
            "safety-passed": safety_passed,
            "safety-reserve-passed": safety_reserve_passed,
            "target-residual": target_residual,
            "warning-cleared": warning_cleared,
            "robustly-cleared": robustly_cleared
        })

    eligible = [candidate for candidate in candidates
                if candidate["heldout-passed"] and
                candidate["safety-passed"] and
                candidate["safety-reserve-passed"] and
                candidate["warning-cleared"] and
                candidate["robustly-cleared"]]
    recommended = min(eligible, key=lambda candidate: candidate["gain"]) \
        if eligible else None

    ensure_directory(diagnostics_dir)
    ensure_directory(reports_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped = File(
        diagnostics_dir,
        "health-warning-candidate-analysis-" + stamp + ".properties")
    latest = File(
        diagnostics_dir,
        "latest-health-warning-candidate-analysis.properties")
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("source", "health-warning-candidate-analysis")
    props.setProperty("state", "analyzed-not-applied")
    props.setProperty("created", stamp)
    props.setProperty("production.promoted-at", promoted_at)
    props.setProperty("source-health", health_file.getCanonicalPath())
    props.setProperty("source-confirmation",
                      confirmation_file.getCanonicalPath())
    props.setProperty("source-heldout", heldout_file.getCanonicalPath())
    props.setProperty("source-production-config",
                      config_file.getCanonicalPath())
    props.setProperty("source-calibration-policy",
                      policy_file.getCanonicalPath())
    props.setProperty("source-production-config.sha256", config_hash_before)
    set_float(props, "current-gain", current_gain)
    props.setProperty("warning.direction", direction)
    set_float(props, "warning.speed-factor", speed)
    set_float(props, "warning.distance.mm", distance)
    set_float(props, "warning.reference.magnitude.mm", reference_magnitude)
    set_float(props, "warning.confirmed.magnitude.mm",
              math.hypot(residual_x, residual_y))
    set_float(props, "warning.target-pass-limit.mm", health_target_limit)
    set_float(props, "warning.target-robust-limit.mm", robust_target_limit)
    set_float(props, "warning.legacy-health-limit.mm", legacy_health_limit)
    set_float(props, "target.user-radial-tolerance.mm", user_target)
    set_float(props, "target.minimum-measurable.mm", minimum_target)
    set_float(props, "safety.maximum-offset.mm", safety_limit)
    set_float(props, "safety.reserve-limit.mm", safety_reserve_limit)
    props.setProperty("candidate.count", str(len(candidates)))
    props.setProperty("recommended-candidate.index", "0")
    for index, candidate in enumerate(candidates, 1):
        prefix = "candidate.{0}".format(index)
        set_float(props, prefix + ".gain", candidate["gain"])
        set_float(props, prefix + ".heldout.rms.mm", candidate["all"]["rms"])
        set_float(props, prefix + ".heldout.p95.mm", candidate["all"]["p95"])
        set_float(props, prefix + ".heldout.maximum.mm",
                  candidate["all"]["maximum"])
        set_float(props, prefix + ".heldout.maximum-worsening.mm",
                  candidate["all"]["maximum-worsening"])
        set_float(props, prefix + ".heldout.a.rms.mm", candidate["a"]["rms"])
        set_float(props, prefix + ".heldout.b.rms.mm", candidate["b"]["rms"])
        set_float(props, prefix + ".maximum-combined-offset.mm",
                  candidate["maximum-offset"])
        set_float(props, prefix + ".warning.predicted-residual.mm",
                  candidate["target-residual"])
        for key in [
                "heldout-passed", "safety-passed", "safety-reserve-passed",
                "warning-cleared", "robustly-cleared"]:
            props.setProperty(prefix + "." + key,
                              str(candidate[key]).lower())
        if recommended is candidate:
            props.setProperty("recommended-candidate.index", str(index))
            set_float(props, "recommended-gain", candidate["gain"])

    config_hash_after = sha256(config_file)
    props.setProperty("source-production-config.sha256-after", config_hash_after)
    props.setProperty("integrity.config-unchanged",
                      str(config_hash_after == config_hash_before).lower())
    write_properties(props, stamped, latest)

    lines = [
        "OpenPnP CoreXY Health Warning Candidate Analysis",
        "",
        "No movement occurred and no compensation configuration was changed.",
        "Production revision: " + promoted_at,
        "Confirmed warning: {0}, speed {1:.0%}, distance {2:.1f} mm".format(
            direction, speed, distance),
        "Accepted / confirmed residual: {0:.6f} / {1:.6f} mm".format(
            reference_magnitude, math.hypot(residual_x, residual_y)),
        "User 2D radial target / robust target: {0:.6f} / {1:.6f} mm".format(
            health_target_limit, robust_target_limit),
        "Vision-derived minimum / previous health-relative limit: "
        "{0:.6f} / {1:.6f} mm".format(
            minimum_target, legacy_health_limit),
        "Maximum offset / 5% reserve limit: {0:.6f} / {1:.6f} mm".format(
            safety_limit, safety_reserve_limit),
        "",
        metric_line("Held-out baseline", all_baseline),
        "",
        "Gain  Heldout RMS  A RMS     B RMS     Worst+    Max offset  Target     Decision"
    ]
    for candidate in candidates:
        decision = "eligible" if candidate in eligible else "held"
        if recommended is candidate:
            decision = "RECOMMENDED"
        lines.append(
            "{0:>3.0f}%  {1:.6f}  {2:.6f}  {3:.6f}  {4:.6f}  "
            "{5:.6f}   {6:.6f}  {7}".format(
                candidate["gain"] * 100.0,
                candidate["all"]["rms"], candidate["a"]["rms"],
                candidate["b"]["rms"],
                candidate["all"]["maximum-worsening"],
                candidate["maximum-offset"],
                candidate["target-residual"], decision))
    lines.extend(["", ("Minimum sufficient trial gain: {0:.0%}".format(
        recommended["gain"]) if recommended is not None else
        "No candidate reaches the user target while satisfying every safeguard."),
        "Selection policy: choose the smallest safe gain whose predicted "
        "2D residual remains inside the user target.",
        "The recommendation is analysis-only and has not been loaded or applied.",
        "Production configuration integrity: " +
        ("PASS" if config_hash_after == config_hash_before else "FAIL")])

    report = File(
        reports_dir,
        "health-warning-candidate-analysis-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
    try:
        writer.write("\r\n".join(lines))
    finally:
        writer.close()
    JOptionPane.showMessageDialog(
        None,
        "\n".join(lines) + "\n\nData: " + stamped.getAbsolutePath() +
        "\nReport: " + report.getAbsolutePath(),
        "CoreXY Health Warning Candidate Analysis",
        JOptionPane.INFORMATION_MESSAGE if recommended is not None
        else JOptionPane.WARNING_MESSAGE)


try:
    analyze()
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Health Warning Candidate Analysis Error",
        JOptionPane.ERROR_MESSAGE)
    raise
