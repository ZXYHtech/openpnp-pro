import sys
from java.io import File, FileInputStream
from java.util import Properties
from javax.swing import JOptionPane
from org.openpnp.machine.reference.axis import ReferenceControllerAxis
from org.openpnp.model import Configuration, LengthUnit, Location
from org.openpnp.spi import MotionPlanner
from org.openpnp.util import MovableUtils, UiUtils


machine = Configuration.get().getMachine()
scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
formal_file = File(runtime_dir, "config/corexy.properties")
jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap


def status_values(text):
    result = {}
    for line in str(text).splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


try:
    status = status_values(CoreXYScriptBootstrap.status())
    active_file = File(status.get("config", ""))
    if not active_file.isFile():
        raise RuntimeError("The active CoreXY configuration cannot be read.")
    active = Properties()
    stream = FileInputStream(active_file)
    try:
        active.load(stream)
    finally:
        stream.close()
    if active.getProperty("trial.kind", "") != "health-warning-gain" or \
            active.getProperty("trial.persistence", "") != "process-only":
        raise RuntimeError("The health-warning transient trial is not loaded.")
    gain = float(active.getProperty("model.gain"))
    if status.get("enabled") != "true" or status.get("dry-run") != "false":
        raise RuntimeError("The transient physical compensation is not active.")
    if not machine.isEnabled() or not machine.isHomed():
        raise RuntimeError("Enable and Home the machine before this test.")
    if status.get("homing-bypass") != "false" or \
            status.get("A-state") != "0" or status.get("B-state") != "0":
        raise RuntimeError("Home again so A/B start at zero after homing bypass.")

    head = machine.getDefaultHead()
    camera = head.getDefaultCamera()
    for axis in [camera.getAxisX(), camera.getAxisY()]:
        if isinstance(axis, ReferenceControllerAxis) and \
                str(axis.getBacklashCompensationMethod()) != "None":
            raise RuntimeError("Disable native X/Y backlash compensation.")
    center = head.getCalibrationPrimaryFiducialLocation().convertToUnits(
        LengthUnit.Millimeters)
    if not center.isInitialized() or abs(center.getX()) < 1e-9 or \
            abs(center.getY()) < 1e-9:
        raise RuntimeError("Configure a non-zero Primary Fiducial X/Y location.")
    x_target = center.add(Location(
        LengthUnit.Millimeters, 20.0, 0.0, 0.0, 0.0))
    b_target = center.add(Location(
        LengthUnit.Millimeters, 20.0, -20.0, 0.0, 0.0))
    for label, target in [("X+20", x_target), ("B+40", b_target)]:
        if not camera.isReachable(target):
            raise RuntimeError(label + " target is outside the reachable area.")

    message = (
        "REAL {0:.0%} CoreXY transient smoke test\n\n"
        "Primary Fiducial -> X+20 -> return -> B+40 -> return\n"
        "Speed factor: 40%; no vision acceptance is performed.\n"
        "The machine will be disabled when the test ends.\n\n"
        "Clear the complete path and keep emergency stop ready."
    ).format(gain)
    answer = JOptionPane.showConfirmDialog(
        None, message, "CoreXY Health Warning Trial Smoke Test",
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
    if answer == JOptionPane.OK_OPTION:
        def move_and_wait(planner, target, speed):
            camera.moveTo(target, speed)
            planner.waitForCompletion(
                camera, MotionPlanner.CompletionType.WaitForStillstand)

        def run_test():
            try:
                planner = machine.getMotionPlanner()
                MovableUtils.moveToLocationAtSafeZ(camera, center, 0.40)
                planner.waitForCompletion(
                    camera, MotionPlanner.CompletionType.WaitForStillstand)
                move_and_wait(planner, x_target, 0.40)
                move_and_wait(planner, center, 0.40)
                move_and_wait(planner, b_target, 0.40)
                move_and_wait(planner, center, 0.40)
                JOptionPane.showMessageDialog(
                    None, "Transient {0:.0%} smoke test completed.\n\n{1}".format(
                        gain, str(CoreXYScriptBootstrap.status())),
                    "CoreXY Health Warning Trial Smoke Test",
                    JOptionPane.INFORMATION_MESSAGE)
            finally:
                machine.setEnabled(False)

        UiUtils.submitUiMachineTask(run_test)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Health Warning Trial Smoke Error",
        JOptionPane.ERROR_MESSAGE)
    raise
