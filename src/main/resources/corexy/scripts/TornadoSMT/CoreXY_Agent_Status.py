import sys
from java.io import File
from javax.swing import JOptionPane
from org.openpnp.model import Configuration

scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")

try:
    jar_path = agent_jar.getCanonicalPath()
    if jar_path not in sys.path:
        sys.path.append(jar_path)
    from org.openpnp.corexy.agent import CoreXYScriptBootstrap
    status = CoreXYScriptBootstrap.status()
    JOptionPane.showMessageDialog(
        None,
        status,
        "OpenPnP CoreXY Agent Status",
        JOptionPane.INFORMATION_MESSAGE,
    )
except Exception as error:
    JOptionPane.showMessageDialog(
        None,
        "CoreXY Agent is not available.\n\n" + str(error),
        "OpenPnP CoreXY Agent Status",
        JOptionPane.ERROR_MESSAGE,
    )
