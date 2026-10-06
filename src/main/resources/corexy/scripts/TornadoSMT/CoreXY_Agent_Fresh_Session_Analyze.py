import math
from java.io import File, FileInputStream, FileOutputStream, OutputStreamWriter
from java.util import Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


runtime_dir = File(Configuration.get().getScripting().getScriptsDirectory(),
                   "TornadoSMT/OpenPnP-CoreXY")
current_file = File(runtime_dir, "sessions/current-session.properties")


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


def summarize(values):
    ordered = sorted(values)
    rms = math.sqrt(sum([value * value for value in ordered]) / len(ordered))
    return rms, ordered[max(0, int(math.ceil(len(ordered) * 0.95)) - 1)], \
        ordered[-1]


def home_values(props):
    count = int(required(props, "grid.speed.count")) * \
        int(required(props, "grid.distance.count"))
    return [float(required(props, "cell.{0}.residual.mm".format(index)))
            for index in range(1, count + 1)]


def percent(before, after):
    return 0.0 if before <= 0.0 else 100.0 * (before - after) / before


try:
    if not current_file.isFile():
        raise IOError("No fresh session exists.")
    session = load_properties(current_file)
    session_id = required(session, "session.id")
    session_dir = File(required(session, "session.directory"))
    names = ["formal-primary", "formal-home-b",
             "uncompensated-primary", "uncompensated-home-b"]
    data = {}
    for name in names:
        path = File(session_dir, name + ".properties")
        if not path.isFile():
            raise IOError("Fresh session measurement is missing: " + name)
        props = load_properties(path)
        if required(props, "state") != "completed":
            raise ValueError("Fresh measurement is incomplete: " + name)
        if required(props, "session.id") != session_id:
            raise ValueError("Fresh measurement belongs to another session: " + name)
        if required(props, "source-production-config.sha256").lower() != \
                required(session, "formal.sha256").lower():
            raise ValueError("Formal hash mismatch: " + name)
        if required(props, "policy.sha256").lower() != \
                required(session, "policy.sha256").lower():
            raise ValueError("Policy hash mismatch: " + name)
        data[name] = props

    fp = data["formal-primary"]
    up = data["uncompensated-primary"]
    formal_primary = [float(required(fp, "current.all." + key + ".mm"))
                      for key in ["rms", "p95", "maximum"]]
    uncomp_primary = [float(required(up, "current.all." + key + ".mm"))
                      for key in ["rms", "p95", "maximum"]]
    formal_home_values = home_values(data["formal-home-b"])
    uncomp_home_values = home_values(data["uncompensated-home-b"])
    formal_home = summarize(formal_home_values)
    uncomp_home = summarize(uncomp_home_values)
    target = float(required(session, "target.radial-tolerance.mm"))
    formal_home_target = len([v for v in formal_home_values if v <= target])
    uncomp_home_target = len([v for v in uncomp_home_values if v <= target])

    primary_improvement = percent(uncomp_primary[0], formal_primary[0])
    home_improvement = percent(uncomp_home[0], formal_home[0])
    state = "READY_FOR_RESIDUAL_FIT" if primary_improvement >= 0.0 else \
        "PRIMARY_REGRESSION"
    lines = [
        "OpenPnP CoreXY Fresh Same-time Dual-anchor Analysis", "",
        "Result: " + state,
        "Session: " + session_id,
        "Target: {0:.6f} mm".format(target), "",
        "Primary all RMS: uncompensated {0:.6f} -> formal {1:.6f} mm "
        "({2:+.1f}% improvement)".format(
            uncomp_primary[0], formal_primary[0], primary_improvement),
        "Primary P95: {0:.6f} -> {1:.6f} mm; maximum: {2:.6f} -> "
        "{3:.6f} mm".format(uncomp_primary[1], formal_primary[1],
                             uncomp_primary[2], formal_primary[2]),
        "Home B RMS: uncompensated {0:.6f} -> formal {1:.6f} mm "
        "({2:+.1f}% improvement)".format(
            uncomp_home[0], formal_home[0], home_improvement),
        "Home B P95: {0:.6f} -> {1:.6f} mm; maximum: {2:.6f} -> "
        "{3:.6f} mm".format(uncomp_home[1], formal_home[1],
                             uncomp_home[2], formal_home[2]),
        "Home cells meeting target: {0} -> {1} / {2}".format(
            uncomp_home_target, formal_home_target, len(formal_home_values)), "",
        "All comparisons use only this session's four hash-matched scans.",
        "Old measurements were not used. No model or configuration was changed."
    ]
    report = File(session_dir, "fresh-session-analysis.txt")
    writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
    try:
        writer.write("\r\n".join(lines))
    finally:
        writer.close()
    JOptionPane.showMessageDialog(
        None, "\n".join(lines) + "\n\nReport: " + report.getAbsolutePath(),
        "CoreXY Fresh Session Analysis",
        JOptionPane.INFORMATION_MESSAGE if state == "READY_FOR_RESIDUAL_FIT"
        else JOptionPane.WARNING_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(None, str(error),
                                  "CoreXY Fresh Session Analysis Error",
                                  JOptionPane.ERROR_MESSAGE)
    raise
