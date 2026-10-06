import sys
from java.io import File
from javax.swing import JOptionPane
from org.openpnp.model import Configuration, LengthUnit, Location
from org.openpnp.util import MovableUtils, UiUtils

machine = Configuration.get().getMachine()
scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap

status = str(CoreXYScriptBootstrap.status())
if "configured=true" not in status or "dry-run=true" not in status:
    raise RuntimeError(
        "This test requires a configured CoreXY Agent with dry-run=true.")
if not machine.isEnabled() or not machine.isHomed():
    raise RuntimeError("Enable and Home the machine before running this test.")

tool = machine.getDefaultHead().getDefaultNozzle()
start = tool.getLocation().convertToUnits(LengthUnit.Millimeters)
delta = Location(LengthUnit.Millimeters, 30.0, -30.0, 0.0, 0.0)
target = start.add(delta)
margin = 1.0


def reachable_with_margin(location):
    offsets = (
        (0.0, 0.0),
        (-margin, -margin),
        (-margin, margin),
        (margin, -margin),
        (margin, margin),
    )
    for dx, dy in offsets:
        probe = location.add(Location(
            LengthUnit.Millimeters, dx, dy, 0.0, 0.0))
        if not tool.isReachable(probe):
            return False
    return True


if not reachable_with_margin(start) or not reachable_with_margin(target):
    raise RuntimeError(
        "The B test start or target is outside the reachable area with "
        "a 1 mm safety margin. Jog to a safer central position first.")

message = (
    "CoreXY Agent dry-run B test\n\n"
    "Tool: {0}\n"
    "Start: X={1:.3f}, Y={2:.3f} mm\n"
    "Target: X={3:.3f}, Y={4:.3f} mm\n\n"
    "Path: X+30, Y-30, then return.\n"
    "This produces dA=0 and dB=+60/-60 mm.\n"
    "Speed factor: 0.25. Physical compensation remains disabled.\n\n"
    "Raise the head and confirm the complete diagonal path is clear."
).format(
    tool.getName(), start.getX(), start.getY(), target.getX(), target.getY())

answer = JOptionPane.showConfirmDialog(
    None,
    message,
    "OpenPnP CoreXY Agent Dry-run B Test",
    JOptionPane.OK_CANCEL_OPTION,
    JOptionPane.WARNING_MESSAGE,
)

if answer == JOptionPane.OK_OPTION:
    def run_test():
        MovableUtils.moveToLocationAtSafeZ(tool, target, 0.25)
        MovableUtils.moveToLocationAtSafeZ(tool, start, 0.25)
        print("[OpenPnP-CoreXY] Dry-run B test completed.")

    UiUtils.submitUiMachineTask(run_test)
