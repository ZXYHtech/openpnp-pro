import sys
from java.io import File, FileInputStream
from java.util import Properties
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
if status.get("configured") != "true" or status.get("mode") != \
        "distance-speed-position-state-offset":
    raise RuntimeError("The dual-reference transient model is not loaded.")
if status.get("enabled") != "true" or status.get("dry-run") != "false":
    raise RuntimeError("This smoke test requires real transient compensation.")
if not machine.isEnabled() or not machine.isHomed():
    raise RuntimeError("Enable and Home the machine before this smoke test.")

trial_file = File(status.get("config", ""))
props = Properties()
stream = FileInputStream(trial_file)
try:
    props.load(stream)
finally:
    stream.close()
if props.getProperty("trial.kind") != "dual-reference-home-b-physical":
    raise RuntimeError("The active config is not the approved physical trial.")

primary_x = float(props.getProperty("spatial.anchor.primary.x.mm"))
primary_y = float(props.getProperty("spatial.anchor.primary.y.mm"))
home_x = float(props.getProperty("spatial.anchor.home.x.mm"))
home_y = float(props.getProperty("spatial.anchor.home.y.mm"))
dx = home_x - primary_x
dy = home_y - primary_y
separation = (dx * dx + dy * dy) ** 0.5
unit_x = dx / separation
unit_y = dy / separation
stations = [
    ("Primary", primary_x, primary_y),
    ("Midpoint", primary_x + 0.5 * dx, primary_y + 0.5 * dy),
    ("Home-inset", home_x - 5.0 * unit_x, home_y - 5.0 * unit_y),
]

head = machine.getDefaultHead()
camera = head.getDefaultCamera()
primary = head.getCalibrationPrimaryFiducialLocation().convertToUnits(
    LengthUnit.Millimeters)
test_distance = 60.0
targets = []
for label, x, y in stations:
    target = Location(LengthUnit.Millimeters, x, y,
                      primary.getZ(), primary.getRotation())
    start = target.add(Location(
        LengthUnit.Millimeters, -test_distance, 0.0, 0.0, 0.0))
    if not camera.isReachable(target) or not camera.isReachable(start):
        raise RuntimeError(label + " smoke-test path is not reachable.")
    targets.append((label, start, target))

answer = JOptionPane.showConfirmDialog(
    None,
    "REAL spatial compensation is active.\n\n"
    "The camera will make one 60 mm X- then X+ return at Primary, midpoint, "
    "and 5 mm inward from Home.\n"
    "Speed: 70%.\n\n"
    "Clear the complete travel area and keep emergency stop ready.\n"
    "The machine will be disabled after the test.",
    "CoreXY Dual-reference Physical Smoke Test",
    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)

if answer == JOptionPane.OK_OPTION:
    def run_test():
        planner = machine.getMotionPlanner()
        lines = []
        try:
            for label, start, target in targets:
                MovableUtils.moveToLocationAtSafeZ(camera, start, 0.70)
                planner.waitForCompletion(
                    camera, MotionPlanner.CompletionType.WaitForStillstand)
                camera.moveTo(target, 0.70)
                planner.waitForCompletion(
                    camera, MotionPlanner.CompletionType.WaitForStillstand)
                values = status_values(CoreXYScriptBootstrap.status())
                lines.append(label + ": weight=" +
                             values.get("spatial-B-weight", "?") +
                             ", offset=" +
                             values.get("active-offset", "?"))
            JOptionPane.showMessageDialog(
                None,
                "Dual-reference physical smoke test completed.\n\n" +
                "\n".join(lines) +
                "\n\nNo formal configuration was changed.",
                "CoreXY Dual-reference Physical Smoke Test",
                JOptionPane.INFORMATION_MESSAGE)
        finally:
            machine.setEnabled(False)

    UiUtils.submitUiMachineTask(run_test)
