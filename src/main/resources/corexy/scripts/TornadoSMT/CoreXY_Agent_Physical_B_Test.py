import math
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
config_file = File(runtime_dir, "config/corexy.properties")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap


def status_values(text):
    values = {}
    for line in str(text).splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def load_properties(path):
    props = Properties()
    stream = FileInputStream(path)
    try:
        props.load(stream)
    finally:
        stream.close()
    return props


def number(props, key):
    value = props.getProperty(key)
    if value is None:
        raise RuntimeError("Missing CoreXY configuration key: " + key)
    result = float(value)
    if math.isnan(result) or math.isinf(result):
        raise RuntimeError("Invalid CoreXY configuration value: " + key)
    return result


status = status_values(CoreXYScriptBootstrap.status())
if status.get("configured") != "true":
    raise RuntimeError("CoreXY Agent is not configured.")
if status.get("enabled") != "true" or status.get("dry-run") != "false":
    raise RuntimeError(
        "This physical test requires enabled=true and dry-run=false.")
if not machine.isEnabled() or not machine.isHomed():
    raise RuntimeError("Enable and Home the machine before running this test.")
if status.get("homing-bypass") != "false":
    raise RuntimeError("CoreXY Home bypass is still active.")
if status.get("A-state") != "0" or status.get("B-state") != "0":
    raise RuntimeError("Home again so the initial A/B states are both zero.")

props = load_properties(config_file)
configured_max = number(props, "safety.max-offset.mm")
if configured_max > 0.0750001:
    raise RuntimeError("Physical trial safety.max-offset.mm exceeds 0.075 mm.")

a_states = (
    (0.0, 0.0),
    (number(props, "offset.a.positive.x.mm"),
     number(props, "offset.a.positive.y.mm")),
    (number(props, "offset.a.negative.x.mm"),
     number(props, "offset.a.negative.y.mm")),
)
b_states = (
    (0.0, 0.0),
    (number(props, "offset.b.positive.x.mm"),
     number(props, "offset.b.positive.y.mm")),
    (number(props, "offset.b.negative.x.mm"),
     number(props, "offset.b.negative.y.mm")),
)

actual_max = 0.0
for a_x, a_y in a_states:
    for b_x, b_y in b_states:
        actual_max = max(actual_max, math.hypot(a_x + b_x, a_y + b_y))
if actual_max > configured_max + 0.0000001:
    raise RuntimeError("Configured A/B offsets exceed the physical trial limit.")

tool = machine.getDefaultHead().getDefaultNozzle()
start = tool.getLocation().convertToUnits(LengthUnit.Millimeters)
delta = Location(LengthUnit.Millimeters, 30.0, -30.0, 0.0, 0.0)
target = start.add(delta)
margin = number(props, "safety.soft-limit-margin.mm")


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
        "The physical B test path is outside the reachable area with "
        "the configured safety margin. Jog to a safer central position first.")

b_x = number(props, "offset.b.positive.x.mm")
b_y = number(props, "offset.b.positive.y.mm")
message = (
    "REAL CoreXY compensation test\n\n"
    "Tool: {0}\n"
    "Start: X={1:.3f}, Y={2:.3f} mm\n"
    "Target: X={3:.3f}, Y={4:.3f} mm\n"
    "B+ offset: X={5:+.6f}, Y={6:+.6f} mm\n"
    "Maximum combined trial offset: {7:.6f} mm\n\n"
    "Path: X+30, Y-30, wait, then return and wait.\n"
    "Speed factor: 0.20. REAL compensation is active.\n\n"
    "The machine will be disabled automatically when the test ends.\n"
    "Raise the head, clear the path, and keep emergency stop ready."
).format(
    tool.getName(), start.getX(), start.getY(), target.getX(), target.getY(),
    b_x, b_y, actual_max)

answer = JOptionPane.showConfirmDialog(
    None,
    message,
    "OpenPnP CoreXY Agent 25 Percent Physical Test",
    JOptionPane.OK_CANCEL_OPTION,
    JOptionPane.WARNING_MESSAGE,
)

if answer == JOptionPane.OK_OPTION:
    def run_test():
        try:
            planner = machine.getMotionPlanner()
            MovableUtils.moveToLocationAtSafeZ(tool, target, 0.20)
            planner.waitForCompletion(
                tool, MotionPlanner.CompletionType.WaitForStillstand)
            print("[OpenPnP-CoreXY] Physical B target status:\n" +
                  str(CoreXYScriptBootstrap.status()))
            MovableUtils.moveToLocationAtSafeZ(tool, start, 0.20)
            planner.waitForCompletion(
                tool, MotionPlanner.CompletionType.WaitForStillstand)
            print("[OpenPnP-CoreXY] Physical B return status:\n" +
                  str(CoreXYScriptBootstrap.status()))
            print("[OpenPnP-CoreXY] Physical B test completed.")
        finally:
            machine.setEnabled(False)
            print("[OpenPnP-CoreXY] Machine disabled after physical B test.")

    UiUtils.submitUiMachineTask(run_test)
