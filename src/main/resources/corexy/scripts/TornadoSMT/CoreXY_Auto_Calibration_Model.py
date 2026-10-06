import re
import sys
from java.io import (File, FileInputStream, FileOutputStream,
                     OutputStreamWriter)
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
config_file = File(runtime_dir, "config/corexy.properties")
policy_file = File(runtime_dir, "config/calibration-policy.properties")
diagnostics_dir = File(runtime_dir, "diagnostics")
candidates_dir = File(runtime_dir, "candidates")
reports_dir = File(runtime_dir, "reports")
pilot_latest = File(diagnostics_dir, "latest-auto-calibration-pilot.properties")
refinement_latest = File(
    diagnostics_dir, "latest-auto-calibration-refinement.properties")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.diagnostics import CoreXYCalibrationSurface


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


def stamped_file(latest, prefix):
    props = load_properties(latest)
    if props.getProperty("state") != "completed":
        raise ValueError(latest.getName() + " is not completed.")
    created = str(props.getProperty("created", ""))
    if re.match(r"^[0-9]{8}-[0-9]{6}$", created):
        stamped = File(diagnostics_dir, prefix + created + ".properties")
        if stamped.isFile():
            return stamped
    return latest


def set_float(props, key, value):
    props.setProperty(key, "{0:.9f}".format(float(value)))


def store_metrics(props, prefix, metrics):
    set_float(props, prefix + ".rms.mm", metrics.getRmsMm())
    set_float(props, prefix + ".p95.mm", metrics.getP95Mm())
    set_float(props, prefix + ".maximum.mm", metrics.getMaximumMm())
    set_float(props, prefix + ".maximum-worsening.mm",
              metrics.getMaximumWorseningMm())


def store_direction(props, key, model):
    prefix = "model." + key
    props.setProperty(prefix + ".direction", str(model.getDirection()))
    set_float(props, prefix + ".speed-bandwidth",
              model.getSpeedBandwidth())
    set_float(props, prefix + ".log-distance-bandwidth",
              model.getDistanceBandwidth())
    set_float(props, prefix + ".minimum-speed", model.getMinimumSpeed())
    set_float(props, prefix + ".maximum-speed", model.getMaximumSpeed())
    set_float(props, prefix + ".minimum-distance.mm",
              model.getMinimumDistanceMm())
    set_float(props, prefix + ".maximum-distance.mm",
              model.getMaximumDistanceMm())
    observations = list(model.getObservations())
    props.setProperty(prefix + ".point.count", str(len(observations)))
    for index, observation in enumerate(observations):
        point = prefix + ".point.{0}".format(index + 1)
        set_float(props, point + ".speed-factor",
                  observation.getSpeedFactor())
        set_float(props, point + ".distance.mm",
                  observation.getDistanceMm())
        set_float(props, point + ".correction.x.mm",
                  observation.getCorrectionXmm())
        set_float(props, point + ".correction.y.mm",
                  observation.getCorrectionYmm())
        set_float(props, point + ".scatter.mm", observation.getScatterMm())
        set_float(props, point + ".detection-floor.mm",
                  observation.getDetectionFloorMm())


def write_properties(props, stamped, latest):
    for output in [stamped, latest]:
        stream = FileOutputStream(output)
        try:
            props.store(stream, "OpenPnP CoreXY calibration surface candidates")
        finally:
            stream.close()


def metrics_line(label, metrics):
    return (
        "{0}: RMS {1:.6f} mm, P95 {2:.6f} mm, maximum {3:.6f} mm, "
        "worst-point worsening {4:.6f} mm"
    ).format(
        label, metrics.getRmsMm(), metrics.getP95Mm(),
        metrics.getMaximumMm(), metrics.getMaximumWorseningMm())


def maximum_combined_offset(result, gain):
    a = [(0.0, 0.0)] + [(point.getCorrectionXmm(), point.getCorrectionYmm())
                        for point in result.getAModel().getObservations()]
    b = [(0.0, 0.0)] + [(point.getCorrectionXmm(), point.getCorrectionYmm())
                        for point in result.getBModel().getObservations()]
    return max([gain * ((left[0] + right[0]) ** 2 +
                        (left[1] + right[1]) ** 2) ** 0.5
                for left in a for right in b])


def select_minimum_necessary_candidate(result, policy, base_config):
    target = float(required(policy, "target.radial-tolerance.mm"))
    floor_multiple = float(required(
        policy, "target.minimum-vision-floor-multiple"))
    floor = result.getAnalysis().getObservedFloorMm()
    minimum_target = floor * floor_multiple
    if target < minimum_target:
        raise ValueError(
            "The user radial target is below the measurable minimum: "
            "{0:.6f} mm.".format(minimum_target))
    safety_limit = float(required(base_config, "safety.max-offset.mm"))
    maximum_residual = target + floor * floor_multiple
    records = []
    for index, candidate in enumerate(list(result.getCandidates())):
        metrics = candidate.getMetrics()
        maximum_offset = maximum_combined_offset(
            result, candidate.getGain())
        policy_eligible = (
            candidate.isEligible() and
            metrics.getP95Mm() <= target and
            metrics.getMaximumMm() <= maximum_residual and
            maximum_offset <= safety_limit)
        records.append({
            "index": index,
            "candidate": candidate,
            "maximum-offset": maximum_offset,
            "policy-eligible": policy_eligible
        })
    eligible = [record for record in records if record["policy-eligible"]]
    recommended = min(
        eligible, key=lambda record: record["candidate"].getGain()) \
        if eligible else None
    return {
        "target": target,
        "minimum-target": minimum_target,
        "maximum-residual": maximum_residual,
        "safety-limit": safety_limit,
        "records": records,
        "recommended": recommended
    }


def build_outputs(result, pilot, refinement, policy, selection, stamp):
    ensure_directory(candidates_dir)
    ensure_directory(reports_dir)
    props = Properties()
    props.setProperty("schema.version", "3")
    props.setProperty("source", "external-auto-calibration-surface")
    props.setProperty("state", "fitted-not-applied")
    props.setProperty("created", stamp)
    props.setProperty("source-pilot", pilot.getCanonicalPath())
    props.setProperty("source-refinement", refinement.getCanonicalPath())
    props.setProperty("source-calibration-policy",
                      policy_file.getCanonicalPath())
    props.setProperty("model.kind", "GAUSSIAN_KERNEL_LOG_DISTANCE_V1")
    props.setProperty("compensation.applied", "false")
    set_float(props, "observed-floor.mm",
              result.getAnalysis().getObservedFloorMm())
    set_float(props, "target.radial-tolerance.mm", selection["target"])
    set_float(props, "target.minimum-measurable.mm",
              selection["minimum-target"])
    set_float(props, "target.maximum-residual-guard.mm",
              selection["maximum-residual"])
    set_float(props, "safety.maximum-offset.mm",
              selection["safety-limit"])
    store_metrics(props, "baseline", result.getBaselineMetrics())
    store_direction(props, "a", result.getAModel())
    store_direction(props, "b", result.getBModel())

    candidates = list(result.getCandidates())
    props.setProperty("candidate.count", str(len(candidates)))
    recommended_record = selection["recommended"]
    recommended = (recommended_record["index"]
                   if recommended_record is not None else -1)
    props.setProperty("recommended-candidate.index",
                      str(recommended + 1 if recommended >= 0 else 0))
    for index, candidate in enumerate(candidates):
        prefix = "candidate.{0}".format(index + 1)
        set_float(props, prefix + ".gain", candidate.getGain())
        props.setProperty(prefix + ".eligible",
                          str(candidate.isEligible()).lower())
        record = selection["records"][index]
        props.setProperty(prefix + ".policy-eligible",
                          str(record["policy-eligible"]).lower())
        set_float(props, prefix + ".maximum-combined-offset.mm",
                  record["maximum-offset"])
        store_metrics(props, prefix, candidate.getMetrics())

    stamped = File(
        candidates_dir, "auto-calibration-surface-" + stamp + ".properties")
    latest = File(
        candidates_dir, "latest-auto-calibration-surface.properties")
    write_properties(props, stamped, latest)

    lines = [
        "OpenPnP CoreXY Automatic Calibration Surface",
        "",
        "State: fitted-not-applied",
        "No movement occurred. No candidate was applied.",
        "Model: GAUSSIAN_KERNEL_LOG_DISTANCE_V1",
        "Source pilot: " + pilot.getCanonicalPath(),
        "Source refinement: " + refinement.getCanonicalPath(),
        "Observations: A={0}, B={1}".format(
            len(list(result.getAModel().getObservations())),
            len(list(result.getBModel().getObservations()))),
        "A bandwidths: speed={0:.3f}, log-distance={1:.3f}".format(
            result.getAModel().getSpeedBandwidth(),
            result.getAModel().getDistanceBandwidth()),
        "B bandwidths: speed={0:.3f}, log-distance={1:.3f}".format(
            result.getBModel().getSpeedBandwidth(),
            result.getBModel().getDistanceBandwidth()),
        "",
        metrics_line("Uncompensated baseline", result.getBaselineMetrics()),
        "User 2D radial target: {0:.6f} mm; measurable minimum: "
        "{1:.6f} mm".format(
            selection["target"], selection["minimum-target"]),
        "Maximum residual guard: {0:.6f} mm; offset safety limit: "
        "{1:.6f} mm".format(
            selection["maximum-residual"], selection["safety-limit"]),
        "",
        "Leave-one-out candidate estimates:"
    ]
    for index, candidate in enumerate(candidates):
        record = selection["records"][index]
        label = "{0:.0%} [{1}; policy {2}; max offset {3:.6f}]".format(
            candidate.getGain(),
            "eligible" if candidate.isEligible() else "held",
            "eligible" if record["policy-eligible"] else "held",
            record["maximum-offset"])
        if index == recommended:
            label += " [recommended for validation]"
        lines.append(metrics_line(label, candidate.getMetrics()))
    lines.extend([
        "",
        ("Minimum sufficient candidate: {0:.0%}".format(
            recommended_record["candidate"].getGain())
         if recommended_record is not None else
         "No candidate reaches the user target inside every safeguard."),
        "Policy eligibility requires P95 within the user target, maximum "
        "within its vision guard, and offset within the safety limit.",
        "Eligibility is an offline safeguard, not approval for production use.",
        "A held-out visual validation is required before any configuration change."
    ])
    report = File(
        reports_dir, "auto-calibration-surface-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
    try:
        writer.write("\r\n".join(lines))
    finally:
        writer.close()
    return stamped, report


try:
    if not pilot_latest.isFile() or not refinement_latest.isFile() or \
            not policy_file.isFile() or not config_file.isFile():
        raise IOError("Completed pilot and refinement sessions are required.")
    pilot = stamped_file(pilot_latest, "auto-calibration-pilot-")
    refinement = stamped_file(
        refinement_latest, "auto-calibration-refinement-")
    result = CoreXYCalibrationSurface.fitFiles(
        pilot.getCanonicalPath(), refinement.getCanonicalPath())
    policy = load_properties(policy_file)
    base_config = load_properties(config_file)
    selection = select_minimum_necessary_candidate(
        result, policy, base_config)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    candidate_file, report_file = build_outputs(
        result, pilot, refinement, policy, selection, stamp)
    lines = [
        "Automatic calibration surface fitted.",
        "",
        "No movement occurred and no candidate was applied.",
        "Samples: A={0}, B={1}".format(
            len(list(result.getAModel().getObservations())),
            len(list(result.getBModel().getObservations()))),
        metrics_line("Uncompensated", result.getBaselineMetrics()),
        ""
    ]
    recommended_record = selection["recommended"]
    recommended = (recommended_record["index"]
                   if recommended_record is not None else -1)
    for index, candidate in enumerate(list(result.getCandidates())):
        record = selection["records"][index]
        suffix = " eligible" if record["policy-eligible"] else " held"
        if index == recommended:
            suffix += "; recommended for held-out validation"
        lines.append(metrics_line(
            "{0:.0%}{1}".format(candidate.getGain(), suffix),
            candidate.getMetrics()))
    lines.extend([
        "",
        "User 2D radial target: {0:.3f} mm".format(selection["target"]),
        ("Minimum sufficient candidate: {0:.0%}".format(
            recommended_record["candidate"].getGain())
         if recommended_record is not None else
         "No candidate reaches the target inside every safeguard."),
        "",
        "Candidate: " + candidate_file.getAbsolutePath(),
        "Report: " + report_file.getAbsolutePath()
    ])
    JOptionPane.showMessageDialog(
        None, "\n".join(lines), "CoreXY Automatic Calibration Surface",
        JOptionPane.INFORMATION_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Automatic Calibration Surface Error",
        JOptionPane.ERROR_MESSAGE)
    raise
