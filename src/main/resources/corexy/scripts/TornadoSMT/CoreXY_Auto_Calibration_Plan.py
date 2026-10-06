import sys
from java.io import File, FileOutputStream
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
diagnostics_dir = File(runtime_dir, "diagnostics")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.diagnostics import CoreXYCalibrationPlanner


def ensure_directory(directory):
    if not directory.isDirectory() and not directory.mkdirs():
        raise IOError("Cannot create directory: " + directory.getAbsolutePath())


def join_values(values, pattern):
    return ", ".join([pattern.format(float(value)) for value in values])


def store_plan(plan, stamp):
    ensure_directory(diagnostics_dir)
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("source", "external-auto-calibration-plan")
    props.setProperty("created", stamp)
    props.setProperty("state", "planned-not-measured")
    props.setProperty("camera.name", str(plan.getCameraName()))
    props.setProperty("axis.x.name", str(plan.getXAxisName()))
    props.setProperty("axis.y.name", str(plan.getYAxisName()))
    primary = plan.getPrimaryFiducial()
    props.setProperty("primary.x.mm", "{0:.9f}".format(primary.getX()))
    props.setProperty("primary.y.mm", "{0:.9f}".format(primary.getY()))
    props.setProperty("primary.z.mm", "{0:.9f}".format(primary.getZ()))
    props.setProperty("path-safety-margin.mm",
                      "{0:.6f}".format(plan.getPathSafetyMarginMm()))
    props.setProperty("available-virtual-distance.mm",
                      "{0:.6f}".format(plan.getAvailableVirtualDistanceMm()))
    props.setProperty("recommended-virtual-distance.mm",
                      "{0:.6f}".format(plan.getRecommendedVirtualDistanceMm()))
    props.setProperty("maximum-physical-axis-excursion.mm",
                      "{0:.6f}".format(plan.getMaximumPhysicalAxisExcursionMm()))

    distances = list(plan.getDistancesMm())
    props.setProperty("distance.count", str(len(distances)))
    for index, distance in enumerate(distances):
        props.setProperty("distance.{0}.mm".format(index + 1),
                          "{0:.6f}".format(float(distance)))
    speeds = list(plan.getSpeedFactors())
    props.setProperty("speed.count", str(len(speeds)))
    for index, speed in enumerate(speeds):
        props.setProperty("speed.{0}.factor".format(index + 1),
                          "{0:.6f}".format(float(speed)))

    stamped = File(diagnostics_dir, "auto-calibration-plan-" + stamp + ".properties")
    latest = File(diagnostics_dir, "latest-auto-calibration-plan.properties")
    for output_file in [stamped, latest]:
        stream = FileOutputStream(output_file)
        try:
            props.store(stream, "OpenPnP CoreXY automatic calibration sampling plan")
        finally:
            stream.close()
    return stamped


try:
    plan = CoreXYCalibrationPlanner.discoverDefaultPlan()
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    output_file = store_plan(plan, stamp)
    distances = join_values(plan.getDistancesMm(), "{0:.1f}")
    speeds = join_values(plan.getSpeedFactors(), "{0:.0%}")
    primary = plan.getPrimaryFiducial()
    text = (
        "OpenPnP CoreXY Automatic Calibration Plan\n\n"
        "No movement has occurred. No candidate or compensation was changed.\n\n"
        "Primary Fiducial: X={0:.3f}, Y={1:.3f}, Z={2:.3f} mm\n"
        "Camera: {3}; axes: {4} / {5}\n"
        "Soft-limit-derived virtual range: {6:.1f} mm\n"
        "Recommended virtual range (75%): {7:.1f} mm\n"
        "Maximum physical X/Y excursion: {8:.1f} mm\n"
        "Additional reachable-area margin: {9:.1f} mm\n\n"
        "Pilot distances: {10} mm\n"
        "Pilot explicit speeds: {11}\n\n"
        "Plan: {12}"
    ).format(
        primary.getX(), primary.getY(), primary.getZ(),
        plan.getCameraName(), plan.getXAxisName(), plan.getYAxisName(),
        plan.getAvailableVirtualDistanceMm(),
        plan.getRecommendedVirtualDistanceMm(),
        plan.getMaximumPhysicalAxisExcursionMm(),
        plan.getPathSafetyMarginMm(), distances, speeds,
        output_file.getAbsolutePath())
    JOptionPane.showMessageDialog(
        None, text, "CoreXY Automatic Calibration Plan",
        JOptionPane.INFORMATION_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Automatic Calibration Plan Error",
        JOptionPane.ERROR_MESSAGE)
    raise
