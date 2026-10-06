import sys
from jarray import zeros
from java.io import File, FileInputStream, FileOutputStream
from java.security import MessageDigest
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
config_file = File(runtime_dir, "config/corexy.properties")
analysis_file = File(
    runtime_dir, "diagnostics/latest-health-warning-candidate-analysis.properties")
confirmation_file = File(
    runtime_dir, "diagnostics/latest-production-health-confirmation.properties")
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


def same_path(left, right):
    return File(str(left)).getCanonicalPath().lower() == \
        File(str(right)).getCanonicalPath().lower()


def write_properties(props, path):
    stream = FileOutputStream(path)
    try:
        props.store(stream, "Temporary CoreXY health-warning gain trial")
    finally:
        stream.close()


try:
    machine = Configuration.get().getMachine()
    if machine.isEnabled() or machine.getMotionPlanner().isHomed():
        raise RuntimeError(
            "Disable and unhome the machine before loading the trial.")
    for path in [config_file, analysis_file, confirmation_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())

    formal_hash = sha256(config_file)
    formal = load_properties(config_file)
    analysis = load_properties(analysis_file)
    confirmation = load_properties(confirmation_file)
    if required(formal, "production.persistence") != "persistent" or \
            required(formal, "enabled") != "true" or \
            required(formal, "dry-run") != "false":
        raise ValueError("The formal production configuration is not active.")
    if required(analysis, "state") != "analyzed-not-applied":
        raise ValueError("The latest warning candidate analysis is not ready.")
    if required(confirmation, "confirmation.result") != "WARNING_CONFIRMED":
        raise ValueError("The source warning is not confirmed.")
    if required(analysis, "integrity.config-unchanged") != "true":
        raise ValueError("The analysis did not preserve configuration integrity.")
    if formal_hash.lower() != \
            required(analysis, "source-production-config.sha256").lower():
        raise ValueError("The formal configuration changed after analysis.")
    if required(formal, "production.promoted-at") != \
            required(analysis, "production.promoted-at") or \
            required(formal, "production.promoted-at") != \
            required(confirmation, "production.promoted-at"):
        raise ValueError("The evidence belongs to another production revision.")
    if not same_path(required(analysis, "source-production-config"), config_file):
        raise ValueError("The analysis references another production config.")

    index = int(required(analysis, "recommended-candidate.index"))
    if index <= 0:
        raise ValueError("No health-warning candidate is recommended.")
    prefix = "candidate.{0}".format(index)
    gain = float(required(analysis, prefix + ".gain"))
    if abs(gain - float(required(analysis, "recommended-gain"))) > 1e-9:
        raise ValueError("Recommended candidate index and gain do not match.")
    for key in ["heldout-passed", "safety-passed",
                "safety-reserve-passed", "warning-cleared",
                "robustly-cleared"]:
        if required(analysis, prefix + "." + key) != "true":
            raise ValueError("Recommended candidate failed: " + key)
    maximum_offset = float(required(
        analysis, prefix + ".maximum-combined-offset.mm"))
    safety_limit = float(required(formal, "safety.max-offset.mm"))
    if maximum_offset > safety_limit + 1e-9:
        raise ValueError("Candidate combined offset exceeds the safety limit.")

    message = (
        "Load REAL CoreXY health-warning trial\n\n"
        "Formal gain: {0:.0%}; trial gain: {1:.0%}\n"
        "Predicted maximum combined offset: {2:.6f} mm\n"
        "Safety limit: {3:.6f} mm\n"
        "Persistence: current OpenPnP process only\n\n"
        "The formal {0:.0%} configuration file will not be changed.\n"
        "Restarting OpenPnP or using Restore Formal returns to {0:.0%}.\n"
        "After loading, Home before any movement.\n\n"
        "Type PHYSICAL {4:.0f} to continue."
    ).format(float(required(formal, "model.gain")), gain,
             maximum_offset, safety_limit, gain * 100.0)
    answer = JOptionPane.showInputDialog(None, message, "")
    expected = "PHYSICAL {0:.0f}".format(gain * 100.0)
    if answer is None or str(answer).strip().upper() != expected:
        raise RuntimeError("Health-warning trial loading was cancelled.")

    trial = Properties()
    trial.putAll(formal)
    for key in list(trial.stringPropertyNames()):
        if str(key).startswith("production.") or str(key).startswith("trial."):
            trial.remove(key)
    trial.setProperty("model.gain", "{0:.9f}".format(gain))
    trial.setProperty("trial.kind", "health-warning-gain")
    trial.setProperty("trial.persistence", "process-only")
    trial.setProperty("trial.source-production-config",
                      config_file.getCanonicalPath())
    trial.setProperty("trial.source-production-config.sha256", formal_hash)
    trial.setProperty("trial.source-analysis", analysis_file.getCanonicalPath())
    trial.setProperty("trial.source-confirmation",
                      confirmation_file.getCanonicalPath())
    trial.setProperty("trial.production-revision",
                      required(formal, "production.promoted-at"))
    trial.setProperty("trial.production-gain",
                      required(formal, "model.gain"))
    trial.setProperty("trial.target-gain", "{0:.9f}".format(gain))
    trial.setProperty("trial.maximum-combined-offset.mm",
                      "{0:.9f}".format(maximum_offset))

    if not trials_dir.isDirectory() and not trials_dir.mkdirs():
        raise IOError("Cannot create trials directory.")
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped = File(trials_dir, "health-warning-gain-" + stamp + ".properties")
    latest = File(trials_dir, "latest-health-warning-gain.properties")
    write_properties(trial, stamped)
    write_properties(trial, latest)
    status = CoreXYScriptBootstrap.loadTransient(stamped.getCanonicalPath())
    if sha256(config_file) != formal_hash:
        CoreXYScriptBootstrap.reload()
        raise RuntimeError("Formal configuration integrity check failed.")
    JOptionPane.showMessageDialog(
        None,
        "Transient REAL {0:.0%} CoreXY trial loaded.\n\n"
        "Home the machine, then run the short smoke test.\n"
        "Restart or Restore Formal returns to the persistent {1:.0%} config.\n\n"
        "Trial: {2}\n\nStatus:\n{3}".format(
            gain, float(required(formal, "model.gain")),
            stamped.getAbsolutePath(), str(status)),
        "OpenPnP CoreXY Health Warning Trial",
        JOptionPane.WARNING_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "OpenPnP CoreXY Health Warning Trial Error",
        JOptionPane.ERROR_MESSAGE)
    raise
