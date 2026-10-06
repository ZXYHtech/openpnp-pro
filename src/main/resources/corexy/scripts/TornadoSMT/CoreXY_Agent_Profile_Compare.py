from java.io import (File, FileInputStream, FileOutputStream,
                     OutputStreamWriter)
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
formal_file = File(runtime_dir, "config/corexy.properties")
fixed_review_file = File(
    runtime_dir, "diagnostics/latest-legacy-fixed-physical-review.properties")
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


def metric(props, prefix, name):
    return float(required(props, prefix + "." + name + ".mm"))


def write_properties(props, stamped, latest):
    for path in [stamped, latest]:
        stream = FileOutputStream(path)
        try:
            props.store(stream, "OpenPnP CoreXY profile comparison")
        finally:
            stream.close()


def line(label, props):
    return (
        "{0}: All RMS {1:.6f}, P95 {2:.6f}, maximum {3:.6f} mm; "
        "A RMS {4:.6f}; B RMS {5:.6f} mm"
    ).format(
        label,
        metric(props, "physical.all", "rms"),
        metric(props, "physical.all", "p95"),
        metric(props, "physical.all", "maximum"),
        metric(props, "physical.a", "rms"),
        metric(props, "physical.b", "rms"))


try:
    if not formal_file.isFile() or not fixed_review_file.isFile():
        raise IOError(
            "The formal surface and fixed-vector review are both required.")
    formal = load_properties(formal_file)
    if required(formal, "mode") != "distance-speed-state-offset" or \
            required(formal, "production.persistence") != "persistent":
        raise ValueError("The current formal profile is not an Agent surface.")
    surface_review_file = File(required(formal, "production.source-review"))
    if not surface_review_file.isFile():
        raise IOError("Formal surface review was not found: " +
                      surface_review_file.getAbsolutePath())
    surface = load_properties(surface_review_file)
    fixed = load_properties(fixed_review_file)
    if required(surface, "validation.passed") != "true":
        raise ValueError("The formal surface review was not accepted.")
    fixed_passed = required(fixed, "validation.passed") == "true"

    surface_rms = metric(surface, "physical.all", "rms")
    fixed_rms = metric(fixed, "physical.all", "rms")
    surface_p95 = metric(surface, "physical.all", "p95")
    fixed_p95 = metric(fixed, "physical.all", "p95")
    surface_max = metric(surface, "physical.all", "maximum")
    fixed_max = metric(fixed, "physical.all", "maximum")
    same_baseline = True
    for group in ["all", "a", "b"]:
        for name in ["rms", "p95", "maximum"]:
            if abs(metric(surface, "baseline." + group, name) -
                   metric(fixed, "baseline." + group, name)) > 0.000001:
                same_baseline = False
    recommendation = "LEGACY_FIXED" if fixed_passed and \
        fixed_rms < surface_rms and fixed_p95 <= surface_p95 and \
        fixed_max <= surface_max else "SURFACE"

    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("state", "completed")
    props.setProperty("created", stamp)
    props.setProperty("comparison.same-baseline", str(same_baseline).lower())
    props.setProperty("surface.source-review",
                      surface_review_file.getCanonicalPath())
    props.setProperty("fixed.source-review",
                      fixed_review_file.getCanonicalPath())
    props.setProperty("fixed.validation-passed", str(fixed_passed).lower())
    props.setProperty("fixed.model-gain", required(fixed, "model.gain"))
    props.setProperty("recommendation", recommendation)
    for profile, source in [("surface", surface), ("fixed", fixed)]:
        for group in ["all", "a", "b"]:
            for name in ["rms", "p95", "maximum"]:
                props.setProperty(
                    profile + "." + group + "." + name + ".mm",
                    "{0:.9f}".format(metric(
                        source, "physical." + group, name)))

    stamped = File(
        diagnostics_dir, "profile-comparison-" + stamp + ".properties")
    latest = File(diagnostics_dir, "latest-profile-comparison.properties")
    write_properties(props, stamped, latest)
    lines = [
        "OpenPnP CoreXY External Agent Profile Comparison",
        "",
        "No movement occurred and no configuration was changed.",
        "Comparison grid compatible: " + str(same_baseline).lower(),
        "",
        line("Agent surface", surface),
        line("Legacy fixed {0:.0f}%".format(
            float(required(fixed, "model.gain")) * 100.0), fixed),
        "",
        "Fixed-vector validation passed: " + str(fixed_passed).lower(),
        "Measured recommendation: " + recommendation,
    ]
    if not same_baseline:
        lines.extend([
            "",
            "WARNING: Baseline metrics differ. Treat this as indicative only;",
            "a fresh same-session comparison is required before promotion."
        ])
    report = File(reports_dir, "profile-comparison-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
    try:
        writer.write("\r\n".join(lines))
    finally:
        writer.close()
    JOptionPane.showMessageDialog(
        None,
        "\n".join(lines) + "\n\nData: " + stamped.getAbsolutePath() +
        "\nReport: " + report.getAbsolutePath(),
        "CoreXY Agent Profile Comparison",
        JOptionPane.INFORMATION_MESSAGE if same_baseline
        else JOptionPane.WARNING_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Agent Profile Comparison Error",
        JOptionPane.ERROR_MESSAGE)
    raise
