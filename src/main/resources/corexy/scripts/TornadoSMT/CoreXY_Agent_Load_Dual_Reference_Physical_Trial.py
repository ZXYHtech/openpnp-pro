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
dry_run_file = File(runtime_dir, "trials/latest-dual-reference-dry-run.properties")
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


def write_properties(props, path):
    stream = FileOutputStream(path)
    try:
        props.store(stream, "Transient CoreXY dual-reference physical trial")
    finally:
        stream.close()


try:
    machine = Configuration.get().getMachine()
    if machine.getMotionPlanner().isHomed():
        raise RuntimeError(
            "Disable or unhome the machine before loading a physical trial.")
    for path in [formal_file, dry_run_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    formal_hash = sha256(formal_file)
    dry_run = load_properties(dry_run_file)
    if required(dry_run, "schema.version") != "4" or \
            required(dry_run, "mode") != \
            "distance-speed-position-state-offset" or \
            required(dry_run, "trial.kind") != \
            "dual-reference-home-b-dry-run":
        raise ValueError("The latest dual-reference dry-run trial is invalid.")
    if required(dry_run,
                "trial.source-production-config.sha256").lower() != \
            formal_hash.lower():
        raise ValueError("Formal configuration changed after dry-run preparation.")

    message = (
        "REAL dual-reference CoreXY compensation will be loaded.\n\n"
        "A: existing production surface\n"
        "B: position interpolation between Primary and Home\n"
        "Maximum combined offset: {0} mm\n"
        "Persistence: current OpenPnP process only\n\n"
        "No formal configuration will be changed.\n"
        "After loading, Home and run only the dual-reference smoke test.\n\n"
        "Type DUAL PHYSICAL to continue."
    ).format(required(dry_run, "safety.max-offset.mm"))
    confirmation = JOptionPane.showInputDialog(None, message, "")
    if confirmation is None or str(confirmation).strip().upper() != \
            "DUAL PHYSICAL":
        raise RuntimeError("Physical trial loading was cancelled.")

    trial = Properties()
    for key in list(dry_run.stringPropertyNames()):
        key = str(key)
        trial.setProperty(key, required(dry_run, key))
    trial.setProperty("enabled", "true")
    trial.setProperty("dry-run", "false")
    trial.setProperty("trial.kind", "dual-reference-home-b-physical")
    trial.setProperty("trial.persistence", "process-only")
    if not trials_dir.isDirectory() and not trials_dir.mkdirs():
        raise IOError("Cannot create trials directory.")
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped = File(trials_dir,
                   "dual-reference-physical-" + stamp + ".properties")
    latest = File(trials_dir, "latest-dual-reference-physical.properties")
    write_properties(trial, stamped)
    write_properties(trial, latest)
    if sha256(formal_file) != formal_hash:
        raise RuntimeError("Formal configuration changed while loading the trial.")
    status = CoreXYScriptBootstrap.loadTransient(stamped.getCanonicalPath())
    JOptionPane.showMessageDialog(
        None,
        "Transient REAL dual-reference model loaded.\n\n"
        "Persistence: current OpenPnP process only\n"
        "Formal corexy.properties remains unchanged.\n\n"
        "Home the machine, then run the dual-reference physical smoke test.\n\n"
        "Trial: " + stamped.getAbsolutePath() + "\n\nStatus:\n" + str(status),
        "OpenPnP CoreXY Dual-reference Physical Trial",
        JOptionPane.WARNING_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "OpenPnP CoreXY Dual-reference Physical Trial Error",
        JOptionPane.ERROR_MESSAGE)
    raise
