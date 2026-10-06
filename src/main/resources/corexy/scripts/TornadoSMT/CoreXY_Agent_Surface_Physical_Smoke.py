import sys
from java.io import File
from javax.swing import JOptionPane
from org.openpnp.machine.reference.axis import ReferenceControllerAxis
from org.openpnp.model import Configuration, LengthUnit, Location
from org.openpnp.spi import MotionPlanner
from org.openpnp.util import MovableUtils, UiUtils


LEGACY_FIXED_SMOKE = bool(globals().get(
    "COREXY_LEGACY_FIXED_SMOKE", False))
machine = Configuration.get().getMachine()
scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
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


status = status_values(CoreXYScriptBootstrap.status())
if status.get("configured") != "true":
    raise RuntimeError("CoreXY Agent is not configured.")
expected_mode = ("state-offset" if LEGACY_FIXED_SMOKE
                 else "distance-speed-state-offset")
if status.get("mode") != expected_mode:
    raise RuntimeError("The expected transient compensation profile is not loaded.")
if LEGACY_FIXED_SMOKE and "legacy-fixed-physical" not in \
        status.get("config", "").lower():
    raise RuntimeError("The active state-offset profile is not a legacy trial.")
if status.get("enabled") != "true" or status.get("dry-run") != "false":
    raise RuntimeError("This test requires enabled=true and dry-run=false.")
if not machine.isEnabled() or not machine.isHomed():
    raise RuntimeError("Enable and Home the machine before this physical test.")
if status.get("homing-bypass") != "false":
    raise RuntimeError("CoreXY Home bypass is still active.")
if status.get("A-state") != "0" or status.get("B-state") != "0":
    raise RuntimeError("Home again so the initial A/B states are both zero.")

head = machine.getDefaultHead()
camera = head.getDefaultCamera()
for axis in [camera.getAxisX(), camera.getAxisY()]:
    if isinstance(axis, ReferenceControllerAxis) and \
            str(axis.getBacklashCompensationMethod()) != "None":
        raise RuntimeError(
            "Disable native X/Y backlash compensation before this test.")
center = head.getCalibrationPrimaryFiducialLocation().convertToUnits(
    LengthUnit.Millimeters)
if not center.isInitialized() or abs(center.getX()) < 0.000000001 or \
        abs(center.getY()) < 0.000000001:
    raise RuntimeError("Configure a non-zero Primary Fiducial X/Y location.")
x_target = center.add(Location(
    LengthUnit.Millimeters, 20.0, 0.0, 0.0, 0.0))
b_target = center.add(Location(
    LengthUnit.Millimeters, 20.0, -20.0, 0.0, 0.0))
for label, target in [("X+20", x_target), ("B+40", b_target)]:
    if not camera.isReachable(target):
        raise RuntimeError(label + " physical target is not reachable.")

message = (
    ("REAL CoreXY legacy fixed-vector smoke test\n\n"
     if LEGACY_FIXED_SMOKE else
     "REAL CoreXY surface smoke test\n\n") +
    "The camera will move to the Primary Fiducial, then run:\n"
    "X+20 -> return, followed by B+40 -> return.\n"
    "Speed factor: 40%.\n\n"
    "The final X- and B- returns restore the zero-reference state.\n"
    "The machine will be disabled automatically when the test ends.\n\n"
    "Raise the head, clear the path, and keep emergency stop ready."
)
answer = JOptionPane.showConfirmDialog(
    None, message, ("CoreXY Legacy Fixed-vector Smoke Test"
                    if LEGACY_FIXED_SMOKE else
                    "CoreXY Surface Physical Smoke Test"),
    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)

if answer == JOptionPane.OK_OPTION:
    def move_and_wait(planner, target, speed):
        camera.moveTo(target, speed)
        planner.waitForCompletion(
            camera, MotionPlanner.CompletionType.WaitForStillstand)

    def run_test():
        planner = machine.getMotionPlanner()
        completed = False
        try:
            MovableUtils.moveToLocationAtSafeZ(camera, center, 0.40)
            planner.waitForCompletion(
                camera, MotionPlanner.CompletionType.WaitForStillstand)

            # Establish known A-/B- zero-reference states at the center.
            move_and_wait(planner, x_target, 0.40)
            move_and_wait(planner, center, 0.40)
            zero_status = str(CoreXYScriptBootstrap.status())
            print("[OpenPnP-CoreXY] Surface physical zero state:\n" + zero_status)

            move_and_wait(planner, b_target, 0.40)
            positive_status = str(CoreXYScriptBootstrap.status())
            print("[OpenPnP-CoreXY] Surface physical B+ state:\n" +
                  positive_status)
            move_and_wait(planner, center, 0.40)
            final_status = str(CoreXYScriptBootstrap.status())
            print("[OpenPnP-CoreXY] Surface physical final state:\n" +
                  final_status)
            completed = True
            JOptionPane.showMessageDialog(
                None,
                (("Legacy fixed-vector" if LEGACY_FIXED_SMOKE else "Surface") +
                 " physical smoke test completed.\n\n" + final_status),
                ("CoreXY Legacy Fixed-vector Smoke Test"
                 if LEGACY_FIXED_SMOKE else
                 "CoreXY Surface Physical Smoke Test"),
                JOptionPane.INFORMATION_MESSAGE)
        finally:
            machine.setEnabled(False)
            print("[OpenPnP-CoreXY] Machine disabled after surface physical " +
                  ("test." if completed else "test failure."))

    UiUtils.submitUiMachineTask(run_test)
