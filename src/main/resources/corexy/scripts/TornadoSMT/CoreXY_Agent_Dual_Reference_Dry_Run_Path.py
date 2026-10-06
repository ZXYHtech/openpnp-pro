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
if status.get("configured") != "true" or status.get("mode") != \
        "distance-speed-position-state-offset":
    raise RuntimeError("The dual-reference transient model is not loaded.")
if status.get("enabled") != "true" or status.get("dry-run") != "true":
    raise RuntimeError("This path requires enabled=true and dry-run=true.")
if not machine.isEnabled() or not machine.isHomed():
    raise RuntimeError("Enable and Home the machine before this dry-run test.")

trial_file = File(status.get("config", ""))
from java.io import FileInputStream
from java.util import Properties
props = Properties()
stream = FileInputStream(trial_file)
try:
    props.load(stream)
finally:
    stream.close()

primary_x = float(props.getProperty("spatial.anchor.primary.x.mm"))
primary_y = float(props.getProperty("spatial.anchor.primary.y.mm"))
home_x = float(props.getProperty("spatial.anchor.home.x.mm"))
home_y = float(props.getProperty("spatial.anchor.home.y.mm"))
dx = home_x - primary_x
dy = home_y - primary_y
separation = (dx * dx + dy * dy) ** 0.5
unit_x = dx / separation
unit_y = dy / separation
inset = 5.0
stations = [
    ("Primary", primary_x, primary_y, 0.40),
    ("Midpoint", primary_x + 0.5 * dx, primary_y + 0.5 * dy, 0.70),
    ("Home-inset", home_x - inset * unit_x,
     home_y - inset * unit_y, 0.90),
]

head = machine.getDefaultHead()
camera = head.getDefaultCamera()
primary = head.getCalibrationPrimaryFiducialLocation().convertToUnits(
    LengthUnit.Millimeters)
test_distance = 120.0
targets = []
for label, x, y, speed in stations:
    target = Location(LengthUnit.Millimeters, x, y,
                      primary.getZ(), primary.getRotation())
    start = target.add(Location(
        LengthUnit.Millimeters, -test_distance, 0.0, 0.0, 0.0))
    if not camera.isReachable(target) or not camera.isReachable(start):
        raise RuntimeError(label + " dry-run path is not reachable.")
    targets.append((label, start, target, speed))

answer = JOptionPane.showConfirmDialog(
    None,
    "The camera will test a 120 mm X- then X+ return at three stations.\n\n"
    "Stations: Primary, midpoint, and 5 mm inward from Home\n"
    "Speeds: 40%, 70%, 90%\n"
    "The Agent calculates spatial offsets but adds no compensation motion.\n\n"
    "Clear the full travel area and keep emergency stop ready.",
    "CoreXY Dual-reference Dry-run Path",
    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)

if answer == JOptionPane.OK_OPTION:
    def run_test():
        planner = machine.getMotionPlanner()
        lines = []
        try:
            for label, start, target, speed in targets:
                MovableUtils.moveToLocationAtSafeZ(camera, start, speed)
                planner.waitForCompletion(
                    camera, MotionPlanner.CompletionType.WaitForStillstand)
                camera.moveTo(target, speed)
                planner.waitForCompletion(
                    camera, MotionPlanner.CompletionType.WaitForStillstand)
                current = str(CoreXYScriptBootstrap.status())
                values = status_values(current)
                line = (label + ": weight=" +
                        values.get("spatial-B-weight", "?") +
                        ", B=" + values.get("modeled-B-offset", "?"))
                lines.append(line)
                print("[OpenPnP-CoreXY] " + line + "\n" + current)
            JOptionPane.showMessageDialog(
                None,
                "Dual-reference dry-run path completed.\n\n"
                "No physical compensation was applied.\n\n" +
                "\n".join(lines) +
                "\n\nThe machine has been disabled.",
                "CoreXY Dual-reference Dry-run Path",
                JOptionPane.INFORMATION_MESSAGE)
        finally:
            machine.setEnabled(False)

    UiUtils.submitUiMachineTask(run_test)
