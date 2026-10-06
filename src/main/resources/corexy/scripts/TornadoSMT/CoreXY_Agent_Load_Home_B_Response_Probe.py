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
policy_file = File(runtime_dir, "config/calibration-policy.properties")
baseline_file = File(
    runtime_dir, "diagnostics/latest-home-reference-baseline.properties")
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


def status_values(text):
    values = {}
    for line in str(text).splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def same_path(left, right):
    return File(str(left)).getCanonicalPath().lower() == \
        File(str(right)).getCanonicalPath().lower()


def write_properties(props, path):
    stream = FileOutputStream(path)
    try:
        props.store(stream, "Transient CoreXY Home B-state response probe")
    finally:
        stream.close()


try:
    machine = Configuration.get().getMachine()
    if machine.isEnabled() or machine.getMotionPlanner().isHomed():
        raise RuntimeError(
            "Disable and unhome the machine before loading a Home probe.")
    for path in [formal_file, policy_file, baseline_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())

    formal = load_properties(formal_file)
    policy = load_properties(policy_file)
    baseline = load_properties(baseline_file)
    formal_hash = sha256(formal_file)
    production_revision = required(formal, "production.promoted-at")
    if required(formal, "production.persistence") != "persistent" or \
            required(formal, "enabled") != "true" or \
            required(formal, "dry-run") != "false":
        raise ValueError("A persistent production configuration is required.")
    if required(baseline, "measurement.mode") != "uncompensated-baseline":
        raise ValueError("The latest Home baseline is not uncompensated.")
    if required(baseline, "production.revision") != production_revision or \
            required(baseline,
                     "source-production-config.sha256").lower() != \
            formal_hash.lower():
        raise ValueError(
            "The Home baseline belongs to another production revision.")
    if required(baseline, "integrity.config-unchanged") != "true":
        raise ValueError("The Home baseline failed its integrity check.")

    detection_floor = float(required(baseline, "vision.detection-floor.mm"))
    safety_limit = float(required(formal, "safety.max-offset.mm"))
    probe_offset = float(policy.getProperty(
        "home-reference.response-probe-offset.mm", "0.040"))
    if probe_offset < detection_floor * 4.0:
        raise ValueError(
            "Home probe offset must be at least four vision-floor units: "
            "{0:.6f} mm.".format(detection_floor * 4.0))
    if probe_offset > safety_limit * 0.8:
        raise ValueError(
            "Home probe offset must not exceed 80% of the configured safety "
            "limit: {0:.6f} mm.".format(safety_limit * 0.8))

    probe_labels = ["x-plus", "x-minus", "y-plus", "y-minus"]
    suggested = "X+"
    for label in probe_labels:
        result = File(
            runtime_dir,
            "diagnostics/latest-home-response-probe-" + label +
            ".properties")
        if not result.isFile() and label == "x-plus":
            result = File(
                runtime_dir,
                "diagnostics/latest-home-response-probe-x.properties")
        elif not result.isFile() and label == "y-minus":
            result = File(
                runtime_dir,
                "diagnostics/latest-home-response-probe-y.properties")
        if not result.isFile():
            suggested = label.replace("-plus", "+").replace("-minus", "-").upper()
            break
    answer = JOptionPane.showInputDialog(
        None,
        "Load an independent Home B-state response probe\n\n"
        "Probe X+: B+ offset ({0:+.3f}, 0.000) mm\n"
        "Probe X-: B+ offset ({1:+.3f}, 0.000) mm\n"
        "Probe Y+: B+ offset (0.000, {0:+.3f}) mm\n"
        "Probe Y-: B+ offset (0.000, {1:+.3f}) mm\n"
        "Suggested next direction: {2}\n"
        "Repeats: at least 11; speed and distance match the baseline.\n"
        "Persistence: current OpenPnP process only.\n\n"
        "The formal 25% production file will not be changed.\n"
        "After loading, enable and Home before measuring.\n"
        "Do not perform production work in probe mode.\n\n"
        "Type PROBE X+, PROBE X-, PROBE Y+, or PROBE Y- to continue.".format(
            probe_offset, -probe_offset, suggested),
        "CoreXY Home Response Probe", JOptionPane.WARNING_MESSAGE)
    if answer is None:
        raise RuntimeError("Home response probe loading was cancelled.")
    token = str(answer).strip().upper()
    allowed = {
        "PROBE X+": ("x", "positive", "x-plus", probe_offset, 0.0),
        "PROBE X-": ("x", "negative", "x-minus", -probe_offset, 0.0),
        "PROBE Y+": ("y", "positive", "y-plus", 0.0, probe_offset),
        "PROBE Y-": ("y", "negative", "y-minus", 0.0, -probe_offset),
    }
    if token not in allowed:
        raise ValueError(
            "Type exactly PROBE X+, PROBE X-, PROBE Y+, or PROBE Y-.")
    axis, probe_sign, probe_label, offset_x, offset_y = allowed[token]

    trial = Properties()
    trial.putAll(formal)
    for key in list(trial.stringPropertyNames()):
        name = str(key)
        if (name.startswith("production.") or name.startswith("trial.") or
                name.startswith("model.") or name.startswith("offset.")):
            trial.remove(key)
    trial.setProperty("schema.version", "1")
    trial.setProperty("mode", "state-offset")
    trial.setProperty("enabled", "true")
    trial.setProperty("dry-run", "false")
    for state in ["a.positive", "a.negative", "b.negative"]:
        trial.setProperty("offset." + state + ".x.mm", "0.000000000")
        trial.setProperty("offset." + state + ".y.mm", "0.000000000")
    trial.setProperty(
        "offset.b.positive.x.mm", "{0:.9f}".format(offset_x))
    trial.setProperty(
        "offset.b.positive.y.mm", "{0:.9f}".format(offset_y))
    trial.setProperty("trial.kind", "home-response-probe")
    trial.setProperty("trial.persistence", "process-only")
    trial.setProperty("trial.probe-axis", axis)
    trial.setProperty("trial.probe-sign", probe_sign)
    trial.setProperty("trial.probe-label", probe_label)
    trial.setProperty("trial.probe-offset.x.mm", "{0:.9f}".format(offset_x))
    trial.setProperty("trial.probe-offset.y.mm", "{0:.9f}".format(offset_y))
    trial.setProperty("trial.probe-repeats", "11")
    trial.setProperty(
        "trial.source-production-config", formal_file.getCanonicalPath())
    trial.setProperty(
        "trial.source-production-config.sha256", formal_hash)
    trial.setProperty(
        "trial.source-baseline", baseline_file.getCanonicalPath())
    trial.setProperty("trial.production-revision", production_revision)
    trial.setProperty("trial.production-gain", required(formal, "model.gain"))
    trial.setProperty(
        "trial.created", SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()))

    if not trials_dir.isDirectory() and not trials_dir.mkdirs():
        raise IOError("Cannot create trials directory.")
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped = File(
        trials_dir, "home-response-probe-" + probe_label + "-" + stamp +
        ".properties")
    latest = File(trials_dir, "latest-home-response-probe.properties")
    axis_latest = File(
        trials_dir, "latest-home-response-probe-" + probe_label +
        ".properties")
    for output in [stamped, latest, axis_latest]:
        write_properties(trial, output)

    status_text = CoreXYScriptBootstrap.loadTransient(
        stamped.getCanonicalPath())
    status = status_values(status_text)
    integrity = (sha256(formal_file) == formal_hash and
                 status.get("enabled") == "true" and
                 status.get("dry-run") == "false" and
                 status.get("mode") == "state-offset" and
                 same_path(status.get("config", ""), stamped))
    if not integrity:
        CoreXYScriptBootstrap.reload()
        raise RuntimeError(
            "Home response probe integrity check failed; formal restored.")

    JOptionPane.showMessageDialog(
        None,
        "Transient REAL B-{0} response probe loaded.\n\n"
        "B+ offset: ({1:+.6f}, {2:+.6f}) mm\n"
        "1. Enable and Home the machine.\n"
        "2. Run Home response probe measurement.\n"
        "3. Disable/unhome before loading the other axis.\n"
        "4. Restore Formal before production.\n\n"
        "Formal configuration SHA-256 is unchanged.\n"
        "Trial: {3}".format(
            probe_label.replace("-plus", "+").replace("-minus", "-").upper(),
            offset_x, offset_y, stamped.getAbsolutePath()),
        "OpenPnP CoreXY Home Response Probe",
        JOptionPane.WARNING_MESSAGE)
except RuntimeError as error:
    if "cancel" not in str(error).lower():
        JOptionPane.showMessageDialog(
            None, str(error), "CoreXY Home Response Probe",
            JOptionPane.ERROR_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Home Response Probe Error",
        JOptionPane.ERROR_MESSAGE)
    raise
