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
baseline_file = File(
    runtime_dir, "diagnostics/latest-home-reference-baseline.properties")
analysis_file = File(
    runtime_dir, "diagnostics/latest-home-response-analysis.properties")
diagnostics_dir = File(runtime_dir, "diagnostics")
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


def validate(props, percent, revision, config_hash, speed, distance,
             analysis_id, analysis_hash):
    if required(props, "measurement.mode") != \
            "response-candidate-" + percent:
        raise ValueError("Candidate " + percent + "% mode does not match.")
    if required(props, "production.revision") != revision or \
            required(props,
                     "source-production-config.sha256").lower() != \
            config_hash.lower():
        raise ValueError("Candidate " + percent + "% is from another revision.")
    if required(props, "integrity.config-unchanged") != "true":
        raise ValueError("Candidate " + percent + "% failed integrity.")
    if required(props, "candidate.analysis-id") != analysis_id or \
            required(props, "candidate.source-analysis.sha256").lower() != \
            analysis_hash.lower():
        raise ValueError(
            "Candidate " + percent + "% belongs to an obsolete response "
            "analysis. Load and measure it again.")
    if abs(number(props, "test.speed-factor") - speed) > 0.000001 or \
            abs(number(props, "test.distance.mm") - distance) > 0.001:
        raise ValueError("Candidate " + percent + "% conditions do not match.")


try:
    for path in [formal_file, baseline_file, analysis_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    formal = load_properties(formal_file)
    baseline = load_properties(baseline_file)
    analysis = load_properties(analysis_file)
    revision = required(formal, "production.promoted-at")
    config_hash = sha256(formal_file)
    analysis_hash = sha256(analysis_file)
    analysis_id = required(analysis, "analysis.id")
    if required(analysis, "analysis.method") != "centered-four-probe-v1":
        raise ValueError("Run the centered four-direction response analysis.")
    speed = number(baseline, "test.speed-factor")
    distance = number(baseline, "test.distance.mm")
    target = number(analysis, "target.radial-tolerance.mm")
    baseline_magnitude = number(baseline, "result.radial-residual.mm")
    minimum_guard = number(
        analysis, "prediction.minimum-validation-guard.mm")

    results = []
    for percent in ["50", "75", "100"]:
        path = File(
            diagnostics_dir,
            "latest-home-response-candidate-" + percent + ".properties")
        if not path.isFile():
            continue
        measured = load_properties(path)
        validate(measured, percent, revision, config_hash, speed, distance,
                 analysis_id, analysis_hash)
        actual_x = number(measured, "result.b-state-difference.x.mm")
        actual_y = number(measured, "result.b-state-difference.y.mm")
        radial = number(measured, "result.radial-residual.mm")
        scatter = number(measured, "result.scatter.mm")
        predicted_x = None
        predicted_y = None
        predicted_radial = None
        for index in range(1, int(required(analysis, "candidate.count")) + 1):
            prefix = "candidate.{0}".format(index)
            if abs(number(analysis, prefix + ".gain") * 100.0 -
                   float(percent)) < 0.001:
                predicted_x = number(
                    analysis, prefix + ".predicted-residual.x.mm")
                predicted_y = number(
                    analysis, prefix + ".predicted-residual.y.mm")
                predicted_radial = number(
                    analysis, prefix + ".predicted-residual.mm")
                break
        if predicted_x is None:
            raise ValueError("Candidate " + percent + "% is absent in analysis.")
        error_x = actual_x - predicted_x
        error_y = actual_y - predicted_y
        error = math.hypot(error_x, error_y)
        guard = max(minimum_guard, scatter)
        results.append({
            "percent": percent,
            "radial": radial,
            "scatter": scatter,
            "robust": radial + scatter,
            "predicted_x": predicted_x,
            "predicted_y": predicted_y,
            "predicted_radial": predicted_radial,
            "error_x": error_x,
            "error_y": error_y,
            "error": error,
            "guard": guard,
            "match": error <= guard,
            "improvement": baseline_magnitude - radial,
            "path": path,
        })

    measured_percents = [item["percent"] for item in results]
    if "50" not in measured_percents or "100" not in measured_percents:
        raise ValueError(
            "Fresh 50% and 100% candidates from this analysis are required.")
    reproducible = [item for item in results if item["match"]]
    passing = [item for item in reproducible if item["radial"] <= target]
    if passing:
        recommended = sorted(
            passing, key=lambda item: float(item["percent"]))[0]
        policy = "minimum prediction-matched candidate meeting user target"
    elif reproducible:
        recommended = sorted(
            reproducible, key=lambda item: item["robust"])[0]
        policy = "lowest robust score among prediction-matched candidates"
    else:
        recommended = None
        policy = "no recommendation because model prediction did not reproduce"
    meaningful = (recommended is not None and
                  recommended["improvement"] > number(
                      baseline, "vision.detection-floor.mm"))

    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    props = Properties()
    props.setProperty("schema.version", "2")
    props.setProperty("source", "home-response-candidate-review")
    props.setProperty("state", "reviewed-not-applied")
    props.setProperty("created", stamp)
    props.setProperty("production.revision", revision)
    props.setProperty("source-production-config.sha256", config_hash)
    props.setProperty("source-analysis", analysis_file.getCanonicalPath())
    props.setProperty("source-analysis.sha256", analysis_hash)
    props.setProperty("analysis.id", analysis_id)
    set_float(props, "baseline.residual.mm", baseline_magnitude)
    set_float(props, "target.radial-tolerance.mm", target)
    props.setProperty("measured-candidate.count", str(len(results)))
    for index, item in enumerate(results, 1):
        prefix = "measured-candidate.{0}".format(index)
        props.setProperty(prefix + ".percent", item["percent"])
        set_float(props, prefix + ".residual.mm", item["radial"])
        set_float(props, prefix + ".scatter.mm", item["scatter"])
        set_float(props, prefix + ".robust-score.mm", item["robust"])
        set_float(props, prefix + ".predicted-residual.x.mm",
                  item["predicted_x"])
        set_float(props, prefix + ".predicted-residual.y.mm",
                  item["predicted_y"])
        set_float(props, prefix + ".predicted-residual.mm",
                  item["predicted_radial"])
        set_float(props, prefix + ".prediction-error.x.mm", item["error_x"])
        set_float(props, prefix + ".prediction-error.y.mm", item["error_y"])
        set_float(props, prefix + ".prediction-error.mm", item["error"])
        set_float(props, prefix + ".prediction-guard.mm", item["guard"])
        props.setProperty(prefix + ".prediction-match",
                          str(item["match"]).lower())
        set_float(props, prefix + ".improvement.mm", item["improvement"])
        props.setProperty(prefix + ".source", item["path"].getCanonicalPath())
    props.setProperty("review.policy", policy)
    props.setProperty("review.recommended-percent",
                      "none" if recommended is None else
                      recommended["percent"])
    props.setProperty("review.meaningful-improvement", str(meaningful).lower())
    if recommended is not None:
        set_float(props, "review.recommended-residual.mm",
                  recommended["radial"])
        set_float(props, "review.recommended-scatter.mm",
                  recommended["scatter"])
        set_float(props, "review.recommended-improvement.mm",
                  recommended["improvement"])
    props.setProperty("review.production-change-allowed", "false")

    stamped = File(
        diagnostics_dir, "home-response-candidate-review-" + stamp +
        ".properties")
    latest = File(
        diagnostics_dir, "latest-home-response-candidate-review.properties")
    for output in [stamped, latest]:
        stream = FileOutputStream(output)
        try:
            props.store(stream, "CoreXY Home response candidate review")
        finally:
            stream.close()

    lines = [
        "OpenPnP CoreXY Home Response Candidate Review", "",
        "No movement occurred and no configuration was changed.",
        "Analysis ID: " + analysis_id,
        "Baseline residual: {0:.6f} mm; user target {1:.6f} mm".format(
            baseline_magnitude, target),
        "Selection policy: " + policy, "",
    ]
    for item in results:
        lines.append(
            "{0}%: measured {1:.6f}; scatter {2:.6f}; robust {3:.6f}; "
            "predicted {4:.6f}; improvement {5:+.6f} mm; vector error "
            "{6:.6f}/{7:.6f}: {8}".format(
                item["percent"], item["radial"], item["scatter"],
                item["robust"], item["predicted_radial"],
                item["improvement"], item["error"], item["guard"],
                "PASS" if item["match"] else "FAIL"))
    lines.append("")
    if recommended is None:
        lines += [
            "Recommended measured candidate: NONE",
            "The centered model did not reproduce within the vector error guard.",
        ]
    else:
        lines += [
            "Recommended measured candidate: " + recommended["percent"] + "%",
            "Measured residual: {0:.6f} mm; improvement {1:+.6f} mm".format(
                recommended["radial"], recommended["improvement"]),
            "Meaningful improvement above one vision-floor unit: " +
            str(meaningful).lower(),
        ]
    lines += ["", "This review does not authorize production use or alter the model."]
    report = File(
        reports_dir, "home-response-candidate-review-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
    try:
        writer.write("\r\n".join(lines))
    finally:
        writer.close()
    JOptionPane.showMessageDialog(
        None, "\n".join(lines) + "\n\nData: " + stamped.getAbsolutePath() +
        "\nReport: " + report.getAbsolutePath(),
        "CoreXY Home Response Candidate Review",
        JOptionPane.INFORMATION_MESSAGE if meaningful else
        JOptionPane.WARNING_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Home Response Candidate Review Error",
        JOptionPane.ERROR_MESSAGE)
    raise
