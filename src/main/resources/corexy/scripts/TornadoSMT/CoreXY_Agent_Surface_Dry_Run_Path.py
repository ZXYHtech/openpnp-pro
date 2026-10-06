import sys
from java.io import File
from javax.swing import JOptionPane
from org.openpnp.model import Configuration, LengthUnit, Location
from org.openpnp.spi import MotionPlanner
from org.openpnp.util import MovableUtils, UiUtils


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
if status.get("mode") != "distance-speed-state-offset":
    raise RuntimeError("The transient distance-speed surface is not loaded.")
if status.get("enabled") != "true" or status.get("dry-run") != "true":
    raise RuntimeError("This test requires enabled=true and dry-run=true.")
if not machine.isEnabled() or not machine.isHomed():
    raise RuntimeError("Enable and Home the machine before this dry-run test.")

head = machine.getDefaultHead()
camera = head.getDefaultCamera()
center = head.getCalibrationPrimaryFiducialLocation().convertToUnits(
    LengthUnit.Millimeters)
if not center.isInitialized() or abs(center.getX()) < 0.000000001 or \
        abs(center.getY()) < 0.000000001:
    raise RuntimeError("Configure a non-zero Primary Fiducial X/Y location.")
vectors = [
    ("X+", 30.0, 0.0, 0.40),
    ("X-", -30.0, 0.0, 0.40),
    ("Y+", 0.0, 30.0, 0.70),
    ("Y-", 0.0, -30.0, 0.70),
    ("A+", 25.0, 25.0, 0.90),
    ("A-", -25.0, -25.0, 0.90),
    ("B+", 25.0, -25.0, 0.90),
    ("B-", -25.0, 25.0, 0.90),
]
targets = []
for label, dx, dy, speed in vectors:
    target = center.add(Location(
        LengthUnit.Millimeters, dx, dy, 0.0, 0.0))
    if not camera.isReachable(target):
        raise RuntimeError(label + " dry-run target is not reachable.")
    targets.append((label, target, speed))

message = (
    "The camera will run an eight-direction path around the Primary Fiducial.\n\n"
    "Cartesian distance: 30 mm; diagonal physical distance: 35.4 mm\n"
    "Explicit speeds: 40%, 70%, 90%\n"
    "The Agent will calculate dynamic A/B surface offsets in dry-run mode.\n"
    "No compensation motion will be added.\n\n"
    "Clear the path and keep emergency stop ready."
)
answer = JOptionPane.showConfirmDialog(
    None, message, "CoreXY Surface Dry-run Path",
    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)

if answer == JOptionPane.OK_OPTION:
    def run_test():
        planner = machine.getMotionPlanner()
        MovableUtils.moveToLocationAtSafeZ(camera, center, 0.40)
        planner.waitForCompletion(
            camera, MotionPlanner.CompletionType.WaitForStillstand)
        for label, target, speed in targets:
            camera.moveTo(target, speed)
            planner.waitForCompletion(
                camera, MotionPlanner.CompletionType.WaitForStillstand)
            print("[OpenPnP-CoreXY] Surface dry-run reached " + label)
            print(str(CoreXYScriptBootstrap.status()))
            camera.moveTo(center, speed)
            planner.waitForCompletion(
                camera, MotionPlanner.CompletionType.WaitForStillstand)
        final_status = str(CoreXYScriptBootstrap.status())
        print("[OpenPnP-CoreXY] Surface dry-run path completed.\n" +
              final_status)
        JOptionPane.showMessageDialog(
            None,
            "Surface dry-run path completed.\n\n"
            "No physical compensation was applied.\n"
            "Review the OpenPnP log for per-segment predictions.\n\n" +
            final_status,
            "CoreXY Surface Dry-run Path",
            JOptionPane.INFORMATION_MESSAGE)

    UiUtils.submitUiMachineTask(run_test)
