import sys
from java.io import File
from javax.swing import JOptionPane
from org.openpnp.model import Configuration

machine = Configuration.get().getMachine()
planner = machine.getMotionPlanner()
if planner.isHomed():
    JOptionPane.showMessageDialog(
        None,
        "Configuration reload is only allowed while the machine is unhomed.\n"
        "Disable the machine or restart OpenPnP after editing corexy.properties.",
        "OpenPnP CoreXY Agent",
        JOptionPane.WARNING_MESSAGE,
    )
else:
    scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
    runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
    agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
    try:
        jar_path = agent_jar.getCanonicalPath()
        if jar_path not in sys.path:
            sys.path.append(jar_path)
        from org.openpnp.corexy.agent import CoreXYScriptBootstrap
        status = CoreXYScriptBootstrap.reload()
        JOptionPane.showMessageDialog(
            None,
            status,
            "OpenPnP CoreXY Agent",
            JOptionPane.INFORMATION_MESSAGE,
        )
    except Exception as error:
        JOptionPane.showMessageDialog(
            None,
            "Configuration reload failed.\n\n" + str(error),
            "OpenPnP CoreXY Agent",
            JOptionPane.ERROR_MESSAGE,
        )
