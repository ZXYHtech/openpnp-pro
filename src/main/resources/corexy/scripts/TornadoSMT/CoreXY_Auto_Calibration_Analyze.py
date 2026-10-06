import re
import sys
from java.io import (File, FileInputStream, FileOutputStream,
                     OutputStreamWriter)
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
diagnostics_dir = File(runtime_dir, "diagnostics")
reports_dir = File(runtime_dir, "reports")
pilot_file = File(diagnostics_dir, "latest-auto-calibration-pilot.properties")
latest_refinement_file = File(
    diagnostics_dir, "latest-auto-calibration-refinement.properties")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.diagnostics import CoreXYCalibrationAnalysis


def ensure_directory(directory):
    if not directory.isDirectory() and not directory.mkdirs():
        raise IOError("Cannot create directory: " + directory.getAbsolutePath())


def set_float(props, key, value):
    props.setProperty(key, "{0:.9f}".format(float(value)))


def load_properties(path):
    props = Properties()
    stream = FileInputStream(path)
    try:
        props.load(stream)
    finally:
        stream.close()
    return props


def compatible_refinement(pilot):
    if not latest_refinement_file.isFile():
        return None
    refinement = load_properties(latest_refinement_file)
    if refinement.getProperty("state") != "completed":
        return None
    for key in ["primary.x.mm", "primary.y.mm", "primary.z.mm",
                "selected-virtual-distance.mm"]:
        if abs(float(pilot.getProperty(key)) -
               float(refinement.getProperty(key))) > 0.01:
            return None
    created = str(refinement.getProperty("created", ""))
    if re.match(r"^[0-9]{8}-[0-9]{6}$", created):
        stamped = File(
            diagnostics_dir,
            "auto-calibration-refinement-" + created + ".properties")
        if stamped.isFile():
            return stamped
    return latest_refinement_file


def summary_line(summary):
    return (
        "{0}: maximum correction {1:.6f} mm; maximum scatter {2:.6f} mm; "
        "distance change {3:.6f} mm; speed change {4:.6f} mm"
    ).format(
        summary.getDirection(), summary.getMaximumMagnitudeMm(),
        summary.getMaximumScatterMm(), summary.getMaximumDistanceChangeMm(),
        summary.getMaximumSpeedChangeMm())


def point_line(index, point):
    return (
        "#{0:02d} {1} {2} speed {3:.1%}, distance {4:.1f} mm, "
        "priority {5:.3f}: {6}"
    ).format(
        index + 1, point.getKind(), point.getDirection(),
        point.getSpeedFactor(), point.getDistanceMm(), point.getPriority(),
        point.getReason())


def store_analysis(analysis, stamp, refinement_file):
    ensure_directory(diagnostics_dir)
    ensure_directory(reports_dir)
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("source", "external-auto-calibration-analysis")
    props.setProperty("source-pilot", pilot_file.getCanonicalPath())
    if refinement_file is not None:
        props.setProperty(
            "source-refinement", refinement_file.getCanonicalPath())
    props.setProperty("created", stamp)
    props.setProperty("state", "analyzed-not-measured")
    props.setProperty("recommended-model", str(analysis.getRecommendedModel()))
    props.setProperty("step-model-rejected",
                      str(analysis.isStepModelRejected()).lower())
    set_float(props, "observed-floor.mm", analysis.getObservedFloorMm())
    for key, summary in [("a", analysis.getASummary()),
                         ("b", analysis.getBSummary())]:
        set_float(props, key + ".maximum-correction.mm",
                  summary.getMaximumMagnitudeMm())
        set_float(props, key + ".maximum-scatter.mm",
                  summary.getMaximumScatterMm())
        set_float(props, key + ".maximum-distance-change.mm",
                  summary.getMaximumDistanceChangeMm())
        set_float(props, key + ".maximum-speed-change.mm",
                  summary.getMaximumSpeedChangeMm())

    points = list(analysis.getRefinementPoints())
    props.setProperty("refinement.count", str(len(points)))
    for index, point in enumerate(points):
        prefix = "refinement.{0}".format(index + 1)
        props.setProperty(prefix + ".kind", str(point.getKind()))
        props.setProperty(prefix + ".direction", str(point.getDirection()))
        set_float(props, prefix + ".speed-factor", point.getSpeedFactor())
        set_float(props, prefix + ".distance.mm", point.getDistanceMm())
        set_float(props, prefix + ".priority", point.getPriority())
        props.setProperty(prefix + ".reason", str(point.getReason()))

    stamped = File(
        diagnostics_dir, "auto-calibration-analysis-" + stamp + ".properties")
    latest = File(diagnostics_dir, "latest-auto-calibration-analysis.properties")
    for output_file in [stamped, latest]:
        stream = FileOutputStream(output_file)
        try:
            props.store(stream, "OpenPnP CoreXY automatic calibration analysis")
        finally:
            stream.close()

    lines = [
        "OpenPnP CoreXY Automatic Calibration Analysis",
        "",
        "Source pilot: " + pilot_file.getCanonicalPath(),
        "Source refinement: " + (
            refinement_file.getCanonicalPath()
            if refinement_file is not None else "none"),
        "Recommended model: " + str(analysis.getRecommendedModel()),
        "Observed vision floor: {0:.6f} mm".format(
            analysis.getObservedFloorMm()),
        "Fixed threshold/step model rejected: " +
        str(analysis.isStepModelRejected()).lower(),
        "",
        summary_line(analysis.getASummary()),
        summary_line(analysis.getBSummary()),
        "",
        "Adaptive refinement plan ({0} measurements):".format(len(points))
    ]
    for index, point in enumerate(points):
        lines.append(point_line(index, point))
    lines.extend([
        "",
        "No refinement movement has occurred.",
        "No compensation candidate was generated or applied."
    ])
    report_file = File(
        reports_dir, "auto-calibration-analysis-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report_file), "UTF-8")
    try:
        writer.write("\r\n".join(lines))
    finally:
        writer.close()
    return stamped, report_file, points


try:
    if not pilot_file.isFile():
        raise IOError("No completed automatic pilot session was found.")
    pilot = load_properties(pilot_file)
    refinement_file = compatible_refinement(pilot)
    if refinement_file is None:
        analysis = CoreXYCalibrationAnalysis.analyzeFile(
            pilot_file.getCanonicalPath())
    else:
        analysis = CoreXYCalibrationAnalysis.analyzeFiles(
            pilot_file.getCanonicalPath(), refinement_file.getCanonicalPath())
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    data_file, report_file, points = store_analysis(
        analysis, stamp, refinement_file)
    preview_count = min(8, len(points))
    preview = "\n".join([
        point_line(index, points[index]) for index in range(preview_count)
    ])
    if len(points) > preview_count:
        preview += "\n... {0} additional points are in the report.".format(
            len(points) - preview_count)
    message = (
        "Recommended model: {0}\n"
        "Fixed threshold/step model rejected: {1}\n"
        "Observed vision floor: {2:.6f} mm\n\n"
        "{3}\n"
        "{4}\n\n"
        "Adaptive refinement points: {5}\n{6}\n\n"
        "Merged refinement: {7}\n"
        "No movement occurred and no candidate was applied.\n\n"
        "Data: {8}\nReport: {9}"
    ).format(
        analysis.getRecommendedModel(),
        str(analysis.isStepModelRejected()).lower(),
        analysis.getObservedFloorMm(),
        summary_line(analysis.getASummary()),
        summary_line(analysis.getBSummary()),
        len(points), preview,
        (refinement_file.getName()
         if refinement_file is not None else "none"),
        data_file.getAbsolutePath(), report_file.getAbsolutePath())
    JOptionPane.showMessageDialog(
        None, message, "CoreXY Automatic Calibration Analysis",
        JOptionPane.INFORMATION_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Automatic Calibration Analysis Error",
        JOptionPane.ERROR_MESSAGE)
    raise
