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
formal_file = File(runtime_dir, "config/corexy.properties")
candidate_file = File(runtime_dir, "candidates/latest-traditional-segmented.properties")
trials_dir = File(runtime_dir, "trials")
if agent_jar.getCanonicalPath() not in sys.path:
    sys.path.append(agent_jar.getCanonicalPath())
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


def write_properties(props, path):
    stream = FileOutputStream(path)
    try:
        props.store(stream, "Temporary traditional segmented CoreXY trial")
    finally:
        stream.close()


try:
    machine = Configuration.get().getMachine()
    if machine.isEnabled() or machine.isHomed():
        raise RuntimeError("Disable the machine before loading this physical trial.")
    for path in [formal_file, candidate_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    formal = load_properties(formal_file)
    candidate = load_properties(candidate_file)
    if required(candidate, "state") != "fitted-not-applied" or \
            required(candidate, "model.kind") != \
            "GAUSSIAN_KERNEL_LOG_DISTANCE_V1" or \
            required(candidate, "strategy.kind") != \
            "TRADITIONAL_DISTANCE_BANDS_V1":
        raise ValueError("The latest traditional segmented candidate is invalid.")
    if sha256(formal_file).lower() != required(candidate, "source-formal.sha256").lower():
        raise ValueError("The formal configuration no longer matches the candidate source.")
    baseline_file = File(required(candidate, "source-baseline"))
    if not baseline_file.isFile() or sha256(baseline_file).lower() != \
            required(candidate, "source-baseline.sha256").lower():
        raise ValueError("The machine-local source baseline is missing or changed.")
    for target_key, source_key in [("axis.x.id", "machine.axis.x.id"),
                                   ("axis.y.id", "machine.axis.y.id")]:
        if required(formal, target_key) != required(candidate, source_key):
            raise ValueError("Candidate axis identity does not match this machine.")
    gain = float(required(candidate, "candidate.gain"))
    expected = "PHYSICAL {0:.0f}".format(gain * 100.0)
    confirmation = JOptionPane.showInputDialog(
        None,
        "Load the machine-local traditional segmented A/B trial.\n\n"
        "A correction: disabled\nB correction: distance bands\n"
        "Gain: {0:.0%}\nMaximum offset: {1:.6f} mm\n"
        "Persistence: current OpenPnP process only\n\n"
        "No formal file will be changed.\nType {2} to continue.".format(
            gain, float(required(candidate, "candidate.maximum-offset.mm")), expected), "")
    if confirmation is None or str(confirmation).strip().upper() != expected:
        raise RuntimeError("Traditional segmented trial loading was cancelled.")

    trial = Properties()
    for key in ["fail-closed", "require-homed", "openpnp.version-policy",
                "openpnp.implementation-version", "axis.x.id", "axis.y.id",
                "speed-factor", "safety.soft-limit-margin.mm"]:
        trial.setProperty(key, required(formal, key))
    trial.setProperty("schema.version", "3")
    trial.setProperty("mode", "distance-speed-state-offset")
    trial.setProperty("enabled", "true")
    trial.setProperty("dry-run", "false")
    trial.setProperty("model.kind", required(candidate, "model.kind"))
    trial.setProperty("strategy.kind", required(candidate, "strategy.kind"))
    trial.setProperty("model.gain", required(candidate, "candidate.gain"))
    trial.setProperty("safety.max-offset.mm", required(candidate, "safety.maximum-offset.mm"))
    trial.setProperty("threshold.a.mm", required(candidate, "model.a.minimum-distance.mm"))
    trial.setProperty("threshold.b.mm", required(candidate, "model.b.minimum-distance.mm"))
    for key in ["target.radial-tolerance.mm", "target.minimum-measurable.mm",
                "target.maximum-residual-guard.mm"]:
        trial.setProperty(key, required(candidate, key))
    for key in list(candidate.stringPropertyNames()):
        key = str(key)
        if key.startswith("model.a.") or key.startswith("model.b."):
            trial.setProperty(key, required(candidate, key))
    trial.setProperty("trial.kind", "traditional-segmented-physical")
    trial.setProperty("trial.source-candidate", candidate_file.getCanonicalPath())
    trial.setProperty("trial.source-validation", baseline_file.getCanonicalPath())
    trial.setProperty("trial.source-validation.sha256", sha256(baseline_file))
    trial.setProperty("trial.persistence", "process-only")
    if not trials_dir.isDirectory() and not trials_dir.mkdirs():
        raise IOError("Cannot create trials directory.")
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped = File(trials_dir, "traditional-segmented-physical-" + stamp + ".properties")
    latest = File(trials_dir, "latest-traditional-segmented-physical.properties")
    write_properties(trial, stamped)
    write_properties(trial, latest)
    status = CoreXYScriptBootstrap.loadTransient(stamped.getCanonicalPath())
    JOptionPane.showMessageDialog(
        None, "Traditional segmented REAL trial loaded.\n\n"
        "Enable and Home the machine, then run the traditional segmented visual validation.\n\n"
        "Trial: " + stamped.getAbsolutePath() + "\n\nStatus:\n" + str(status),
        "CoreXY Traditional Segmented Trial", JOptionPane.WARNING_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(None, str(error),
                                  "CoreXY Traditional Segmented Trial Error",
                                  JOptionPane.ERROR_MESSAGE)
    raise
