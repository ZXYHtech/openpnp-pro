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
candidate_file = File(
    runtime_dir, "candidates/latest-dual-reference-home-b.properties")
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
        props.store(stream, "Transient CoreXY dual-reference dry-run trial")
    finally:
        stream.close()


def build_trial(formal, candidate, detection_floor, dry_run):
    trial = Properties()
    for key in list(formal.stringPropertyNames()):
        key = str(key)
        trial.setProperty(key, required(formal, key))
    trial.setProperty("schema.version", "4")
    trial.setProperty("mode", "distance-speed-position-state-offset")
    trial.setProperty("enabled", "true")
    trial.setProperty("dry-run", "true" if dry_run else "false")
    trial.setProperty("spatial.kind", "CLAMPED_ANCHOR_PROJECTION_V1")
    trial.setProperty("spatial.anchor.primary.x.mm",
                      required(candidate, "anchor.primary.x.mm"))
    trial.setProperty("spatial.anchor.primary.y.mm",
                      required(candidate, "anchor.primary.y.mm"))
    trial.setProperty("spatial.anchor.home.x.mm",
                      required(candidate, "anchor.home.x.mm"))
    trial.setProperty("spatial.anchor.home.y.mm",
                      required(candidate, "anchor.home.y.mm"))
    trial.setProperty("spatial.primary-protection-radius.mm",
                      candidate.getProperty(
                          "primary.protection-radius.mm", "0.000000000"))
    trial.setProperty("model.home-b.direction", "B")
    trial.setProperty("model.home-b.gain", "1.000000000")
    trial.setProperty("model.home-b.speed-bandwidth", "0.200000000")
    trial.setProperty("model.home-b.log-distance-bandwidth", "0.180000000")
    count = int(required(candidate, "home-b.point.count"))
    speeds = []
    distances = []
    for index in range(1, count + 1):
        source = "home-b.point.{0}".format(index)
        target = "model.home-b.point.{0}".format(index)
        speed = float(required(candidate, source + ".speed-factor"))
        distance = float(required(candidate, source + ".distance.mm"))
        speeds.append(speed)
        distances.append(distance)
        trial.setProperty(target + ".speed-factor", "{0:.9f}".format(speed))
        trial.setProperty(target + ".distance.mm", "{0:.9f}".format(distance))
        trial.setProperty(target + ".correction.x.mm",
                          required(candidate, source + ".home-b.x.mm"))
        trial.setProperty(target + ".correction.y.mm",
                          required(candidate, source + ".home-b.y.mm"))
        trial.setProperty(target + ".scatter.mm",
                          required(candidate, source + ".scatter.mm"))
        trial.setProperty(target + ".detection-floor.mm", detection_floor)
    trial.setProperty("model.home-b.point.count", str(count))
    trial.setProperty("model.home-b.minimum-speed", "{0:.9f}".format(min(speeds)))
    trial.setProperty("model.home-b.maximum-speed", "{0:.9f}".format(max(speeds)))
    trial.setProperty("model.home-b.minimum-distance.mm",
                      "{0:.9f}".format(min(distances)))
    trial.setProperty("model.home-b.maximum-distance.mm",
                      "{0:.9f}".format(max(distances)))
    trial.setProperty("trial.kind", "dual-reference-home-b-" +
                      ("dry-run" if dry_run else "physical"))
    trial.setProperty("trial.candidate-id", required(candidate, "candidate.id"))
    trial.setProperty("target.radial-tolerance.mm", required(
        candidate, "target.radial-tolerance.mm"))
    maximum_guard = candidate.getProperty("target.maximum-residual-guard.mm")
    if maximum_guard is not None and str(maximum_guard).strip():
        trial.setProperty(
            "target.maximum-residual-guard.mm", str(maximum_guard).strip())
    else:
        target = float(required(candidate, "target.radial-tolerance.mm"))
        trial.setProperty(
            "target.maximum-residual-guard.mm",
            "{0:.9f}".format(target + detection_floor * 3.0))
    trial.setProperty("trial.source-candidate", candidate_file.getCanonicalPath())
    trial.setProperty("trial.source-candidate.sha256", sha256(candidate_file))
    trial.setProperty("trial.source-production-config",
                      formal_file.getCanonicalPath())
    trial.setProperty("trial.source-production-config.sha256",
                      sha256(formal_file))
    trial.setProperty("trial.persistence", "process-only")
    return trial


try:
    machine = Configuration.get().getMachine()
    if machine.getMotionPlanner().isHomed():
        raise RuntimeError(
            "Disable or unhome the machine before loading a transient trial.")
    for path in [formal_file, candidate_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    formal_hash = sha256(formal_file)
    formal = load_properties(formal_file)
    candidate = load_properties(candidate_file)
    if required(formal, "schema.version") != "3" or \
            required(formal, "mode") != "distance-speed-state-offset":
        raise ValueError("The formal configuration is not a production surface.")
    if required(candidate, "source") != \
            "dual-reference-home-b-surface-analysis" or \
            required(candidate, "candidate.mode") != "dual-anchor-diagonal-v1":
        raise ValueError("The latest dual-reference candidate is not supported.")
    if required(candidate, "production.revision") != \
            required(formal, "production.promoted-at") or \
            required(candidate, "source-production-config.sha256").lower() != \
            formal_hash.lower():
        raise ValueError("Candidate and formal production revision do not match.")
    if required(candidate, "integrity.config-unchanged") != "true" or \
            required(candidate, "summary.spatial-difference-meaningful") != "true":
        raise ValueError("The candidate failed its integrity or significance check.")
    scan_file = File(required(candidate, "source-home-scan"))
    if not scan_file.isFile() or sha256(scan_file).lower() != \
            required(candidate, "source-home-scan.sha256").lower():
        raise ValueError("The source Home scan is missing or has changed.")
    scan = load_properties(scan_file)
    detection_floor = required(scan, "vision.detection-floor.mm")
    trial = build_trial(formal, candidate, detection_floor, True)

    if not trials_dir.isDirectory() and not trials_dir.mkdirs():
        raise IOError("Cannot create trials directory.")
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped = File(trials_dir,
                   "dual-reference-dry-run-" + stamp + ".properties")
    latest = File(trials_dir, "latest-dual-reference-dry-run.properties")
    write_properties(trial, stamped)
    write_properties(trial, latest)
    if sha256(formal_file) != formal_hash:
        raise RuntimeError("Formal configuration changed while preparing the trial.")
    status = CoreXYScriptBootstrap.loadTransient(stamped.getCanonicalPath())
    JOptionPane.showMessageDialog(
        None,
        "Transient dual-reference B surface loaded.\n\n"
        "Mode: dry-run; no physical compensation will be added\n"
        "Spatial rule: clamped projection from Primary to Home\n"
        "A surface: unchanged production model\n"
        "B surface: Primary/Home interpolation\n"
        "Persistence: current OpenPnP process only\n\n"
        "Home the machine, then run the dual-reference dry-run path.\n\n"
        "Trial: " + stamped.getAbsolutePath() + "\n\nStatus:\n" + str(status),
        "OpenPnP CoreXY Dual-reference Dry Run",
        JOptionPane.INFORMATION_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "OpenPnP CoreXY Dual-reference Dry Run Error",
        JOptionPane.ERROR_MESSAGE)
    raise
