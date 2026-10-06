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
analysis_file = File(
    runtime_dir, "diagnostics/latest-home-response-analysis.properties")
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
        props.store(stream, "Transient CoreXY Home response candidate")
    finally:
        stream.close()


try:
    machine = Configuration.get().getMachine()
    if machine.isEnabled() or machine.getMotionPlanner().isHomed():
        raise RuntimeError(
            "Disable and unhome the machine before loading a Home candidate.")
    for path in [formal_file, analysis_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())

    formal = load_properties(formal_file)
    analysis = load_properties(analysis_file)
    formal_hash = sha256(formal_file)
    revision = required(formal, "production.promoted-at")
    if required(formal, "production.persistence") != "persistent" or \
            required(formal, "enabled") != "true" or \
            required(formal, "dry-run") != "false":
        raise ValueError("A persistent production configuration is required.")
    if required(analysis, "state") != "analyzed-not-applied":
        raise ValueError("The latest Home response analysis is not ready.")
    if required(analysis, "analysis.method") != "centered-four-probe-v1":
        raise ValueError(
            "The latest response analysis is obsolete. Run the four-direction "
            "Home response analysis before loading a candidate.")
    if required(analysis, "production.revision") != revision or \
            required(analysis,
                     "source-production-config.sha256").lower() != \
            formal_hash.lower():
        raise ValueError("The response analysis belongs to another production revision.")
    if required(analysis, "identification.quality") == "WEAK":
        raise ValueError(
            "The response identification is weak; do not load candidates.")

    answer = JOptionPane.showInputDialog(
        None,
        "Load a transient Home response validation candidate\n\n"
        "Recommended sequence: validate 50% first, then 100%.\n"
        "The offsets are total B+ state offsets, not additions to production.\n"
        "Only the Home diagnostic may be run in this mode.\n"
        "Persistence: current OpenPnP process only.\n\n"
        "Type CANDIDATE 50, CANDIDATE 75, or CANDIDATE 100.",
        "CoreXY Home Response Candidate", JOptionPane.WARNING_MESSAGE)
    if answer is None:
        raise RuntimeError("Home response candidate loading was cancelled.")
    token = str(answer).strip().upper()
    allowed = {"CANDIDATE 50": 0.50,
               "CANDIDATE 75": 0.75,
               "CANDIDATE 100": 1.00}
    if token not in allowed:
        raise ValueError(
            "Type exactly CANDIDATE 50, CANDIDATE 75, or CANDIDATE 100.")
    gain = allowed[token]

    candidate_index = None
    count = int(required(analysis, "candidate.count"))
    for index in range(1, count + 1):
        if abs(float(required(
                analysis, "candidate.{0}.gain".format(index))) - gain) < 0.000001:
            candidate_index = index
            break
    if candidate_index is None:
        raise ValueError("The requested candidate is absent from the analysis.")
    prefix = "candidate.{0}".format(candidate_index)
    offset_x = float(required(analysis, prefix + ".offset.x.mm"))
    offset_y = float(required(analysis, prefix + ".offset.y.mm"))
    magnitude = float(required(analysis, prefix + ".offset.magnitude.mm"))
    predicted = float(required(
        analysis, prefix + ".predicted-residual.mm"))
    predicted_x = float(required(
        analysis, prefix + ".predicted-residual.x.mm"))
    predicted_y = float(required(
        analysis, prefix + ".predicted-residual.y.mm"))
    safety = float(required(formal, "safety.max-offset.mm"))
    if magnitude > safety + 0.0000001:
        raise ValueError("The candidate exceeds the formal safety limit.")

    confirmation = JOptionPane.showInputDialog(
        None,
        "REAL process-only Home candidate {0:.0%}\n\n"
        "B+ offset: ({1:+.6f}, {2:+.6f}) mm\n"
        "Magnitude: {3:.6f} mm; safety limit: {4:.6f} mm\n"
        "Predicted Home residual: {5:.6f} mm\n"
        "Identification quality: {6}\n\n"
        "The formal production file remains unchanged.\n"
        "Type HOME CANDIDATE {7:.0f} to load.".format(
            gain, offset_x, offset_y, magnitude, safety, predicted,
            required(analysis, "identification.quality"), gain * 100.0),
        "CoreXY Home Candidate Confirmation", JOptionPane.WARNING_MESSAGE)
    expected = "HOME CANDIDATE {0:.0f}".format(gain * 100.0)
    if confirmation is None or \
            str(confirmation).strip().upper() != expected:
        raise RuntimeError("Home response candidate loading was cancelled.")

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
    trial.setProperty("trial.kind", "home-response-candidate")
    trial.setProperty("trial.persistence", "process-only")
    trial.setProperty("trial.candidate-gain", "{0:.9f}".format(gain))
    trial.setProperty("trial.candidate-percent", "{0:.0f}".format(gain * 100.0))
    trial.setProperty("trial.candidate-offset.x.mm", "{0:.9f}".format(offset_x))
    trial.setProperty("trial.candidate-offset.y.mm", "{0:.9f}".format(offset_y))
    trial.setProperty("trial.candidate-offset.magnitude.mm",
                      "{0:.9f}".format(magnitude))
    trial.setProperty("trial.candidate-predicted-residual.mm",
                      "{0:.9f}".format(predicted))
    trial.setProperty("trial.candidate-predicted-residual.x.mm",
                      "{0:.9f}".format(predicted_x))
    trial.setProperty("trial.candidate-predicted-residual.y.mm",
                      "{0:.9f}".format(predicted_y))
    trial.setProperty("trial.probe-repeats", "11")
    trial.setProperty(
        "trial.source-production-config", formal_file.getCanonicalPath())
    trial.setProperty(
        "trial.source-production-config.sha256", formal_hash)
    trial.setProperty("trial.source-analysis", analysis_file.getCanonicalPath())
    trial.setProperty("trial.source-analysis.sha256", sha256(analysis_file))
    trial.setProperty("trial.analysis-id", required(analysis, "analysis.id"))
    trial.setProperty("trial.production-revision", revision)
    trial.setProperty(
        "trial.created", SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()))

    if not trials_dir.isDirectory() and not trials_dir.mkdirs():
        raise IOError("Cannot create trials directory.")
    percent = "{0:.0f}".format(gain * 100.0)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped = File(
        trials_dir, "home-response-candidate-" + percent + "-" + stamp +
        ".properties")
    latest = File(trials_dir, "latest-home-response-candidate.properties")
    percent_latest = File(
        trials_dir, "latest-home-response-candidate-" + percent + ".properties")
    for output in [stamped, latest, percent_latest]:
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
            "Home response candidate integrity check failed; formal restored.")

    JOptionPane.showMessageDialog(
        None,
        "Transient REAL Home candidate {0}% loaded.\n\n"
        "B+ offset: ({1:+.6f}, {2:+.6f}) mm\n"
        "Enable and Home, then run Home candidate measurement.\n"
        "Restore Formal before any production work.\n\n"
        "Trial: {3}".format(
            percent, offset_x, offset_y, stamped.getAbsolutePath()),
        "OpenPnP CoreXY Home Response Candidate",
        JOptionPane.WARNING_MESSAGE)
except RuntimeError as error:
    if "cancel" not in str(error).lower():
        JOptionPane.showMessageDialog(
            None, str(error), "CoreXY Home Response Candidate",
            JOptionPane.ERROR_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Home Response Candidate Error",
        JOptionPane.ERROR_MESSAGE)
    raise
