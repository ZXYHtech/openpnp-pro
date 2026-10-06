import sys
from java.io import File, FileInputStream, FileOutputStream
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
base_file = File(runtime_dir, "config/corexy.properties")
candidate_file = File(
    runtime_dir, "candidates/latest-auto-calibration-surface.properties")
validation_file = File(
    runtime_dir, "diagnostics/latest-auto-calibration-validation.properties")
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


def copy_key(source, target, key):
    target.setProperty(key, required(source, key))


def write_properties(props, path):
    stream = FileOutputStream(path)
    try:
        props.store(stream, "Temporary CoreXY distance-speed physical trial")
    finally:
        stream.close()


try:
    machine = Configuration.get().getMachine()
    if machine.getMotionPlanner().isHomed():
        raise RuntimeError(
            "Disable or unhome the machine before loading a physical trial.")
    for path in [base_file, candidate_file, validation_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    base = load_properties(base_file)
    candidate = load_properties(candidate_file)
    validation = load_properties(validation_file)
    if required(candidate, "state") != "fitted-not-applied":
        raise ValueError("The latest surface candidate is not ready.")
    if required(validation, "state") != "passed" or \
            required(validation, "validation.passed") != "true":
        raise ValueError("The latest held-out validation did not pass.")
    index = int(required(candidate, "recommended-candidate.index"))
    if index <= 0:
        raise ValueError("No candidate is recommended for physical trial.")
    candidate_prefix = "candidate.{0}".format(index)
    if required(candidate, candidate_prefix + ".eligible") != "true":
        raise ValueError("The recommended candidate is not eligible.")
    if required(candidate, candidate_prefix + ".policy-eligible") != "true":
        raise ValueError(
            "The recommended candidate does not meet the user residual target.")
    gain = required(candidate, candidate_prefix + ".gain")
    if abs(float(gain) - float(required(validation, "candidate-gain"))) > 1e-9:
        raise ValueError("Candidate and validation gains do not match.")

    message = (
        "REAL CoreXY distance-speed compensation will be loaded.\n\n"
        "Candidate gain: {0:.0%}\n"
        "Maximum combined offset limit: 0.075 mm\n"
        "Persistence: current OpenPnP process only\n\n"
        "No production file will be changed.\n"
        "After loading, Home the machine and run only the physical smoke test.\n"
        "A restart restores the formal disabled configuration.\n\n"
        "Type PHYSICAL {1:.0f} to continue."
    ).format(float(gain), float(gain) * 100.0)
    confirmation = JOptionPane.showInputDialog(None, message, "")
    expected = "PHYSICAL {0:.0f}".format(float(gain) * 100.0)
    if confirmation is None or str(confirmation).strip().upper() != expected:
        raise RuntimeError("Physical trial loading was cancelled.")

    trial = Properties()
    trial.setProperty("schema.version", "3")
    trial.setProperty("mode", "distance-speed-state-offset")
    trial.setProperty("enabled", "true")
    trial.setProperty("dry-run", "false")
    for key in [
            "fail-closed", "require-homed", "openpnp.version-policy",
            "openpnp.implementation-version", "axis.x.id", "axis.y.id",
            "speed-factor", "safety.soft-limit-margin.mm"]:
        copy_key(base, trial, key)
    trial.setProperty("safety.max-offset.mm", "0.075000000")
    trial.setProperty("threshold.a.mm",
                      required(candidate, "model.a.minimum-distance.mm"))
    trial.setProperty("threshold.b.mm",
                      required(candidate, "model.b.minimum-distance.mm"))
    trial.setProperty("model.kind", required(candidate, "model.kind"))
    trial.setProperty("model.gain", gain)
    for key in ["target.radial-tolerance.mm",
                "target.minimum-measurable.mm",
                "target.maximum-residual-guard.mm"]:
        copy_key(candidate, trial, key)
    for key in list(candidate.stringPropertyNames()):
        key = str(key)
        if key.startswith("model.a.") or key.startswith("model.b."):
            trial.setProperty(key, required(candidate, key))
    trial.setProperty("trial.source-candidate", candidate_file.getCanonicalPath())
    trial.setProperty("trial.source-validation", validation_file.getCanonicalPath())
    trial.setProperty("trial.persistence", "process-only")

    if not trials_dir.isDirectory() and not trials_dir.mkdirs():
        raise IOError("Cannot create trials directory.")
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped = File(trials_dir, "surface-physical-" + stamp + ".properties")
    latest = File(trials_dir, "latest-surface-physical.properties")
    write_properties(trial, stamped)
    write_properties(trial, latest)
    status = CoreXYScriptBootstrap.loadTransient(stamped.getCanonicalPath())
    JOptionPane.showMessageDialog(
        None,
        "Transient REAL distance-speed surface loaded.\n\n"
        "Gain: {0:.0%}\nMaximum offset: 0.075 mm\n"
        "Persistence: current OpenPnP process only\n\n"
        "Home the machine, then run CoreXY_Agent_Surface_Physical_Smoke.py.\n\n"
        "Trial: {1}\n\nStatus:\n{2}".format(
            float(gain), stamped.getAbsolutePath(), str(status)),
        "OpenPnP CoreXY Physical Trial",
        JOptionPane.WARNING_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "OpenPnP CoreXY Physical Trial Error",
        JOptionPane.ERROR_MESSAGE)
    raise
