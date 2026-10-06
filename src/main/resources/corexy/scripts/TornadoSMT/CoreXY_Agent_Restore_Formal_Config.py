import sys
from java.io import File
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap


try:
    machine = Configuration.get().getMachine()
    if machine.getMotionPlanner().isHomed():
        raise RuntimeError(
            "Disable or unhome the machine before restoring formal configuration.")
    status = CoreXYScriptBootstrap.reload()
    JOptionPane.showMessageDialog(
        None,
        "Formal CoreXY configuration restored for this process.\n\n" +
        str(status),
        "OpenPnP CoreXY Formal Configuration",
        JOptionPane.INFORMATION_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "OpenPnP CoreXY Formal Configuration Error",
        JOptionPane.ERROR_MESSAGE)
    raise
