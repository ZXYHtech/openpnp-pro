import math
import sys
from jarray import zeros
from java.io import File, FileInputStream, FileOutputStream
from java.security import MessageDigest
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
tornado_dir = File(scripts_dir, "TornadoSMT")
runtime_dir = File(tornado_dir, "OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
formal_file = File(runtime_dir, "config/corexy.properties")
policy_file = File(runtime_dir, "config/calibration-policy.properties")
legacy_file = File(tornado_dir, "CoreXY_Diagnostic_Candidate.properties")
fresh_file = File(
    runtime_dir, "candidates/latest-fresh-fixed-vector.properties")
current_session_file = File(
    runtime_dir, "sessions/current-session.properties")
candidates_dir = File(runtime_dir, "candidates")
trials_dir = File(runtime_dir, "trials")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap


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


def write_properties(props, path, comment):
    stream = FileOutputStream(path)
    try:
        props.store(stream, comment)
    finally:
        stream.close()


def ensure_directory(path):
    if not path.isDirectory() and not path.mkdirs():
        raise IOError("Cannot create directory: " + path.getAbsolutePath())


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


def copy_key(source, target, key):
    target.setProperty(key, required(source, key))


try:
    machine = Configuration.get().getMachine()
    if machine.isEnabled() or machine.getMotionPlanner().isHomed():
        raise RuntimeError(
            "Disable and unhome the machine before loading a fixed-vector trial.")
    for path in [formal_file, policy_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())

    formal = load_properties(formal_file)
    policy = load_properties(policy_file)
    source_file = fresh_file if fresh_file.isFile() else legacy_file
    if not source_file.isFile():
        raise IOError("No fresh or legacy fixed-vector candidate was found.")
    legacy = load_properties(source_file)
    fresh_candidate = (
        legacy.getProperty("source", "") ==
        "external-fresh-fixed-vector-analysis")
    if required(formal, "production.persistence") != "persistent":
        raise ValueError("The current formal configuration is not persistent.")
    if required(formal, "mode") != "distance-speed-state-offset":
        raise ValueError(
            "The formal comparison profile must be the Agent surface model.")
    if fresh_candidate:
        if required(legacy, "state") != "fitted-not-applied":
            raise ValueError("The fresh fixed-vector candidate is incomplete.")
        if required(legacy, "source-formal.sha256").lower() != \
                sha256(formal_file).lower():
            raise ValueError("The formal configuration changed after fitting.")
        baseline_file = File(required(
            legacy, "source-uncompensated-baseline"))
        if not baseline_file.isFile() or \
                required(legacy,
                         "source-uncompensated-baseline.sha256").lower() != \
                sha256(baseline_file).lower():
            raise ValueError("The fresh uncompensated baseline changed.")
        if not current_session_file.isFile() or required(
                load_properties(current_session_file), "session.id") != \
                required(legacy, "session.id"):
            raise ValueError("The fixed-vector candidate is not from the "
                             "current fresh session.")
    else:
        if required(legacy, "validation_status") != "improved" or \
                required(legacy, "validation_non_degrading") != "true":
            raise ValueError("The legacy candidate was not previously validated.")
    if required(legacy, "corexy_x_axis_id") != required(formal, "axis.x.id") or \
            required(legacy, "corexy_y_axis_id") != required(formal, "axis.y.id"):
        raise ValueError("Legacy candidate axis IDs do not match the formal profile.")

    if not fresh_candidate:
        source_trial = File(required(formal, "production.source-trial"))
        if not source_trial.isFile():
            raise IOError("Formal source trial was not found: " +
                          source_trial.getAbsolutePath())
        surface_trial = load_properties(source_trial)
        baseline_file = File(required(surface_trial, "trial.source-validation"))
        if not baseline_file.isFile():
            raise IOError("Uncompensated comparison baseline was not found: " +
                          baseline_file.getAbsolutePath())

    gain_text = JOptionPane.showInputDialog(
        None,
        "Load the legacy fixed A/B vector through the external Agent.\n\n"
        "Allowed staged gains: 25, 50, 75, or 100 percent.\n"
        "Start with 25. Higher gains require a new smoke test and validation.\n"
        "The formal surface configuration will not be changed.\n\n"
        "Enter gain percent:",
        "Legacy Fixed-vector Agent Trial",
        JOptionPane.WARNING_MESSAGE)
    if gain_text is None:
        raise RuntimeError("Fixed-vector trial loading was cancelled.")
    gain_percent = int(str(gain_text).strip())
    if gain_percent not in [25, 50, 75, 100]:
        raise ValueError("Gain must be one of 25, 50, 75, or 100.")
    gain = gain_percent / 100.0

    a_x = float(required(legacy, "corexy_a_positive_x_mm")) * gain
    a_y = float(required(legacy, "corexy_a_positive_y_mm")) * gain
    b_x = float(required(legacy, "corexy_b_positive_x_mm")) * gain
    b_y = float(required(legacy, "corexy_b_positive_y_mm")) * gain
    combined = math.hypot(a_x + b_x, a_y + b_y)
    absolute_maximum = float(required(policy, "target.absolute-maximum.mm"))
    if combined > absolute_maximum + 0.000000001:
        raise ValueError(
            "Scaled fixed vector exceeds target.absolute-maximum.mm.")

    expected = "LOAD FIXED {0}".format(gain_percent)
    confirmation = JOptionPane.showInputDialog(
        None,
        "Legacy fixed-vector candidate\n\n"
        "Gain: {0}%\n"
        "A+: ({1:+.6f}, {2:+.6f}) mm\n"
        "B+: ({3:+.6f}, {4:+.6f}) mm\n"
        "Maximum A+B vector: {5:.6f} mm\n"
        "Policy absolute maximum: {6:.6f} mm\n"
        "Persistence: current OpenPnP process only\n\n"
        "No formal configuration will be changed.\n"
        "Type {7} to continue.".format(
            gain_percent, a_x, a_y, b_x, b_y, combined,
            absolute_maximum, expected),
        "Legacy Fixed-vector Agent Trial",
        JOptionPane.WARNING_MESSAGE)
    if confirmation is None or str(confirmation).strip().upper() != expected:
        raise RuntimeError("Fixed-vector trial loading was cancelled.")

    ensure_directory(candidates_dir)
    ensure_directory(trials_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    candidate = Properties()
    candidate.setProperty("schema.version", "1")
    candidate.setProperty("state", "legacy-imported-not-applied")
    candidate.setProperty("created", stamp)
    candidate.setProperty("model.kind", "LEGACY_FIXED_VECTOR_V1")
    candidate.setProperty("source-fixed-candidate", source_file.getCanonicalPath())
    candidate.setProperty("source-fixed-candidate.sha256", sha256(source_file))
    candidate.setProperty("source-formal-surface", formal_file.getCanonicalPath())
    candidate.setProperty("source-formal-surface.sha256", sha256(formal_file))
    candidate.setProperty("source-uncompensated-baseline",
                          baseline_file.getCanonicalPath())
    candidate.setProperty(
        "candidate.origin", "fresh-session" if fresh_candidate else "legacy")
    if not fresh_candidate:
        candidate.setProperty("legacy.validation-status",
                              required(legacy, "validation_status"))
        candidate.setProperty("legacy.validation-worst-residual.mm",
                              required(legacy, "validation_worst_residual_mm"))
    for key in ["corexy_min_move_a_mm", "corexy_min_move_b_mm",
                "corexy_a_positive_x_mm", "corexy_a_positive_y_mm",
                "corexy_b_positive_x_mm", "corexy_b_positive_y_mm",
                "corexy_speed_factor", "corexy_x_axis_id",
                "corexy_y_axis_id"]:
        copy_key(legacy, candidate, key)
    candidate_file = File(
        candidates_dir, "legacy-fixed-vector-" + stamp + ".properties")
    candidate_latest = File(
        candidates_dir, "latest-legacy-fixed-vector.properties")
    write_properties(candidate, candidate_file,
                     "Imported legacy fixed-vector candidate")
    write_properties(candidate, candidate_latest,
                     "Imported legacy fixed-vector candidate")

    trial = Properties()
    trial.setProperty("schema.version", "1")
    trial.setProperty("mode", "state-offset")
    trial.setProperty("enabled", "true")
    trial.setProperty("dry-run", "false")
    for key in ["fail-closed", "require-homed", "openpnp.version-policy",
                "openpnp.implementation-version", "axis.x.id", "axis.y.id",
                "safety.soft-limit-margin.mm"]:
        copy_key(formal, trial, key)
    trial.setProperty("threshold.a.mm",
                      required(legacy, "corexy_min_move_a_mm"))
    trial.setProperty("threshold.b.mm",
                      required(legacy, "corexy_min_move_b_mm"))
    trial.setProperty("offset.a.positive.x.mm", "{0:.9f}".format(a_x))
    trial.setProperty("offset.a.positive.y.mm", "{0:.9f}".format(a_y))
    trial.setProperty("offset.a.negative.x.mm", "0.000000000")
    trial.setProperty("offset.a.negative.y.mm", "0.000000000")
    trial.setProperty("offset.b.positive.x.mm", "{0:.9f}".format(b_x))
    trial.setProperty("offset.b.positive.y.mm", "{0:.9f}".format(b_y))
    trial.setProperty("offset.b.negative.x.mm", "0.000000000")
    trial.setProperty("offset.b.negative.y.mm", "0.000000000")
    trial.setProperty("speed-factor", required(legacy, "corexy_speed_factor"))
    trial.setProperty("safety.max-offset.mm",
                      "{0:.9f}".format(absolute_maximum))
    trial.setProperty("model.kind", "LEGACY_FIXED_VECTOR_V1")
    trial.setProperty("model.gain", "{0:.9f}".format(gain))
    trial.setProperty("target.radial-tolerance.mm",
                      required(policy, "target.radial-tolerance.mm"))
    effective_minimum = policy.getProperty(
        "target.effective-minimum.mm", "0.021883000")
    trial.setProperty("target.minimum-measurable.mm", effective_minimum)
    trial.setProperty("target.maximum-residual-guard.mm",
                      "{0:.9f}".format(
                          float(required(policy, "target.radial-tolerance.mm")) +
                          float(effective_minimum)))
    trial.setProperty("trial.kind", "legacy-fixed-vector-physical")
    trial.setProperty("trial.persistence", "process-only")
    trial.setProperty("trial.source-candidate", candidate_file.getCanonicalPath())
    trial.setProperty("trial.source-candidate.sha256", sha256(candidate_file))
    trial.setProperty("trial.source-validation", baseline_file.getCanonicalPath())
    trial.setProperty("trial.source-formal", formal_file.getCanonicalPath())
    trial.setProperty("trial.source-formal.sha256", sha256(formal_file))

    stamped = File(trials_dir,
                   "legacy-fixed-physical-{0}-{1}.properties".format(
                       gain_percent, stamp))
    latest = File(trials_dir, "latest-legacy-fixed-physical.properties")
    for stale in [
            File(runtime_dir,
                 "diagnostics/latest-legacy-fixed-physical-validation.properties"),
            File(runtime_dir,
                 "diagnostics/latest-legacy-fixed-physical-review.properties"),
            File(runtime_dir, "diagnostics/latest-profile-comparison.properties")]:
        if stale.isFile() and not stale.delete():
            raise IOError("Cannot clear stale latest result: " +
                          stale.getAbsolutePath())
    write_properties(trial, stamped, "Transient legacy fixed-vector trial")
    write_properties(trial, latest, "Transient legacy fixed-vector trial")
    status = CoreXYScriptBootstrap.loadTransient(stamped.getCanonicalPath())
    JOptionPane.showMessageDialog(
        None,
        "Legacy fixed-vector trial loaded through the Agent.\n\n"
        "Gain: {0}%\nMaximum A+B vector: {1:.6f} mm\n"
        "Formal surface unchanged.\n\n"
        "Enable and Home the machine, then run the fixed-vector smoke test.\n\n"
        "Trial: {2}\n\nStatus:\n{3}".format(
            gain_percent, combined, stamped.getAbsolutePath(), str(status)),
        "Legacy Fixed-vector Agent Trial", JOptionPane.WARNING_MESSAGE)
except RuntimeError as error:
    if "cancel" not in str(error).lower():
        JOptionPane.showMessageDialog(
            None, str(error), "Legacy Fixed-vector Trial",
            JOptionPane.ERROR_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "Legacy Fixed-vector Trial Error",
        JOptionPane.ERROR_MESSAGE)
    raise
