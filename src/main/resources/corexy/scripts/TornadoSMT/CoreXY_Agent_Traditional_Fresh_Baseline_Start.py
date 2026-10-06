from java.lang import String
from java.io import File, FileInputStream
from java.nio.file import Files
from java.util import Properties
from org.openpnp.model import Configuration
import sys


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()


def run_hidden(name):
    source = scripts_dir.toPath().resolve("TornadoSMT/" + name).toFile()
    if not source.isFile():
        raise IOError("Required hidden workflow script is missing: " +
                      source.getAbsolutePath())
    code = unicode(String(Files.readAllBytes(source.toPath()), "UTF-8"))
    exec compile(code, source.getAbsolutePath(), "exec") in dict(globals())


current = scripts_dir.toPath().resolve(
    "TornadoSMT/OpenPnP-CoreXY/sessions/current-session.properties").toFile()
before = current.lastModified() if current.isFile() else -1
run_hidden("CoreXY_Agent_Fresh_Session_Start.pytxt")
if not current.isFile() or current.lastModified() == before:
    raise RuntimeError("A new same-time session was not created.")
run_hidden("CoreXY_Agent_Load_Home_B_Baseline.pytxt")

runtime_dir = scripts_dir.toPath().resolve(
    "TornadoSMT/OpenPnP-CoreXY").toFile()
agent_jar = runtime_dir.toPath().resolve(
    "lib/openpnp-corexy-agent.jar").toFile()
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


status = status_values(CoreXYScriptBootstrap.status())
active = status.get("config", "")
active_file = File(active)
active_config = (load_properties(active_file)
                 if active_file.isFile() else Properties())
if status.get("enabled") != "false" or \
        status.get("dry-run") != "false" or \
        active_config.getProperty("trial.kind", "") != \
        "home-reference-baseline":
    raise RuntimeError(
        "The fresh session was created, but uncompensated baseline mode "
        "was not loaded. The session was preserved; disable/unhome the "
        "machine and click the calibration button again.")
