from jarray import zeros
from java.io import (File, FileInputStream, FileOutputStream,
                     OutputStreamWriter)
from java.security import MessageDigest
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
formal_file = File(runtime_dir, "config/corexy.properties")
candidate_file = File(
    runtime_dir, "candidates/latest-dual-reference-home-b.properties")
home_file = File(
    runtime_dir, "diagnostics/latest-dual-reference-home-validation.properties")
primary_file = File(
    runtime_dir, "diagnostics/latest-dual-reference-primary-validation.properties")
diagnostics_dir = File(runtime_dir, "diagnostics")
reports_dir = File(runtime_dir, "reports")


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


def number(props, key):
    return float(required(props, key))


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


def set_float(props, key, value):
    props.setProperty(key, "{0:.9f}".format(float(value)))


def ensure_directory(path):
    if not path.isDirectory() and not path.mkdirs():
        raise IOError("Cannot create directory: " + path.getAbsolutePath())


def write_properties(props, stamped, latest):
    for output in [stamped, latest]:
        stream = FileOutputStream(output)
        try:
            props.store(stream, "CoreXY dual-reference visual review")
        finally:
            stream.close()


try:
    for path in [formal_file, candidate_file, home_file, primary_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    formal_hash = sha256(formal_file)
    formal = load_properties(formal_file)
    candidate = load_properties(candidate_file)
    home = load_properties(home_file)
    primary = load_properties(primary_file)
    candidate_id = required(candidate, "candidate.id")
    if required(candidate, "source-production-config.sha256").lower() != \
            formal_hash.lower():
        raise ValueError("The candidate belongs to another formal configuration.")
    if required(home, "source-production-config.sha256").lower() != \
            formal_hash.lower() or \
            required(primary, "source-production-config.sha256").lower() != \
            formal_hash.lower():
        raise ValueError(
            "A visual validation belongs to another formal configuration.")
    if required(home, "candidate.id") != candidate_id or \
            required(primary, "candidate.id") != candidate_id:
        raise ValueError("Home and Primary results did not test the same candidate.")
    if required(home, "state") not in ["passed", "failed-acceptance"] or \
            required(primary, "state") not in ["passed", "failed-acceptance"]:
        raise ValueError("A visual validation did not complete.")
    home_passed = required(home, "validation.passed") == "true"
    primary_passed = required(primary, "validation.passed") == "true"
    passed = home_passed and primary_passed

    ensure_directory(diagnostics_dir)
    ensure_directory(reports_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped = File(
        diagnostics_dir, "dual-reference-review-" + stamp + ".properties")
    latest = File(
        diagnostics_dir, "latest-dual-reference-review.properties")
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("source", "dual-reference-two-anchor-visual-review")
    props.setProperty("state", "passed" if passed else "failed")
    props.setProperty("created", stamp)
    props.setProperty("candidate.id", candidate_id)
    props.setProperty("production.revision", required(
        formal, "production.promoted-at"))
    props.setProperty("source-production-config.sha256", formal_hash)
    props.setProperty("source-home-validation", home_file.getCanonicalPath())
    props.setProperty("source-primary-validation",
                      primary_file.getCanonicalPath())
    props.setProperty("home.validation-passed", str(home_passed).lower())
    props.setProperty("primary.validation-passed", str(primary_passed).lower())
    props.setProperty("review.passed", str(passed).lower())
    props.setProperty("review.production-authorized", "false")
    props.setProperty(
        "review.next-state", "controlled-promotion-design" if passed
        else "candidate-rejected-or-refine")
    for key in ["baseline.rms.mm", "physical.rms.mm",
                "physical.improvement.percent", "baseline.p95.mm",
                "physical.p95.mm", "baseline.maximum.mm",
                "physical.maximum.mm"]:
        set_float(props, "home." + key, number(home, key))
    for key in ["baseline.all.rms.mm", "physical.all.rms.mm",
                "baseline.all.p95.mm", "physical.all.p95.mm",
                "baseline.all.maximum.mm", "physical.all.maximum.mm"]:
        set_float(props, "primary." + key, number(primary, key))
    write_properties(props, stamped, latest)

    home_before = number(home, "baseline.rms.mm")
    home_after = number(home, "physical.rms.mm")
    primary_before = number(primary, "baseline.all.rms.mm")
    primary_after = number(primary, "physical.all.rms.mm")
    lines = [
        "OpenPnP CoreXY Dual-reference Visual Review", "",
        "Review state: " + ("PASSED" if passed else "FAILED"),
        "Candidate: " + candidate_id,
        "No movement occurred and no configuration was changed.", "",
        "Home spatial validation: " + ("PASS" if home_passed else "FAIL"),
        "Home RMS: {0:.6f} -> {1:.6f} mm ({2:+.1f}% improvement)".format(
            home_before, home_after,
            100.0 * (home_before - home_after) / home_before),
        "Home P95: {0:.6f} -> {1:.6f} mm".format(
            number(home, "baseline.p95.mm"),
            number(home, "physical.p95.mm")),
        "Home maximum: {0:.6f} -> {1:.6f} mm".format(
            number(home, "baseline.maximum.mm"),
            number(home, "physical.maximum.mm")), "",
        "Primary regression: " + ("PASS" if primary_passed else "FAIL"),
        "Primary all RMS: {0:.6f} -> {1:.6f} mm ({2:+.1f}% change)".format(
            primary_before, primary_after,
            100.0 * (primary_after - primary_before) / primary_before),
        "Primary P95: {0:.6f} -> {1:.6f} mm".format(
            number(primary, "baseline.all.p95.mm"),
            number(primary, "physical.all.p95.mm")),
        "Primary maximum: {0:.6f} -> {1:.6f} mm".format(
            number(primary, "baseline.all.maximum.mm"),
            number(primary, "physical.all.maximum.mm")), "",
        ("Both anchors passed. The candidate may proceed to controlled "
         "promotion design, but is not yet authorized for production."
         if passed else
         "The candidate did not pass both anchors and must not be promoted."),
    ]
    report = File(reports_dir,
                  "dual-reference-review-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
    try:
        writer.write("\r\n".join(lines))
    finally:
        writer.close()
    JOptionPane.showMessageDialog(
        None, "\n".join(lines) + "\n\nReview: " +
        stamped.getAbsolutePath() + "\nReport: " + report.getAbsolutePath(),
        "CoreXY Dual-reference Visual Review",
        JOptionPane.INFORMATION_MESSAGE if passed else
        JOptionPane.WARNING_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Dual-reference Visual Review Error",
        JOptionPane.ERROR_MESSAGE)
    raise
