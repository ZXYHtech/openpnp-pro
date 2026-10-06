import sys
from java.io import File, FileInputStream, FileOutputStream
from java.nio.file import AtomicMoveNotSupportedException, Files
from java.nio.file import StandardCopyOption
from java.text import SimpleDateFormat
from java.util import Date, Properties
from org.openpnp import Main
from org.openpnp.machine.reference.axis import ReferenceControllerAxis
from org.openpnp.model import Configuration
from org.openpnp.spi import Axis


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
config_file = File(runtime_dir, "config/corexy.properties")


def load_properties(path):
    props = Properties()
    stream = FileInputStream(path)
    try:
        props.load(stream)
    finally:
        stream.close()
    return props


def store_properties_atomic(props, path, comment):
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    temp = File(path.getParentFile(), path.getName() + "." + stamp + ".tmp")
    stream = FileOutputStream(temp)
    try:
        props.store(stream, comment)
    finally:
        stream.close()
    try:
        try:
            Files.move(temp.toPath(), path.toPath(),
                       StandardCopyOption.ATOMIC_MOVE,
                       StandardCopyOption.REPLACE_EXISTING)
        except AtomicMoveNotSupportedException:
            Files.move(temp.toPath(), path.toPath(),
                       StandardCopyOption.REPLACE_EXISTING)
    finally:
        if temp.isFile():
            temp.delete()


def initialize_new_machine_template(path):
    props = load_properties(path)
    if props.getProperty("onboarding.state", "") != "uninitialized":
        return None
    if props.getProperty("enabled", "false").lower() != "false":
        raise RuntimeError(
            "New-machine bootstrap configuration must remain disabled.")
    machine = Configuration.get().getMachine()
    head = machine.getDefaultHead()
    camera = head.getDefaultCamera()
    x_axis = camera.getAxisX()
    y_axis = camera.getAxisY()
    if not isinstance(x_axis, ReferenceControllerAxis) or not isinstance(
            y_axis, ReferenceControllerAxis):
        raise RuntimeError(
            "The top camera must resolve to ReferenceControllerAxis X and Y axes.")
    if x_axis.getType() != Axis.Type.X or y_axis.getType() != Axis.Type.Y:
        raise RuntimeError(
            "The top-camera axes are not configured as Cartesian X and Y.")
    if x_axis.getId() == y_axis.getId():
        raise RuntimeError("The detected X and Y controller axes are identical.")
    if x_axis.getDriver() is None or x_axis.getDriver() != y_axis.getDriver():
        raise RuntimeError(
            "The detected X and Y controller axes must use the same driver.")

    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    backups = File(runtime_dir, "backups")
    if not backups.isDirectory() and not backups.mkdirs():
        raise IOError("Cannot create the CoreXY backups directory.")
    template_backup = File(
        backups, "corexy-new-machine-template-" + stamp + ".properties")
    Files.copy(path.toPath(), template_backup.toPath(),
               StandardCopyOption.REPLACE_EXISTING)

    props.setProperty("axis.x.id", x_axis.getId())
    props.setProperty("axis.y.id", y_axis.getId())
    props.setProperty("openpnp.implementation-version", Main.getBuildString())
    props.setProperty("onboarding.state", "ready-for-calibration")
    props.setProperty("onboarding.initialized-at", stamp)
    props.setProperty("production.promoted-at", "bootstrap-" + stamp)
    props.setProperty("production.persistence", "persistent")
    props.setProperty("production.acceptance", "bootstrap-new-machine")
    props.setProperty("production.source", "new-machine-auto-initialization")
    props.setProperty("enabled", "false")
    props.setProperty("dry-run", "false")
    props.setProperty("model.kind", "FRESH_FIXED_VECTOR_V1")
    props.setProperty("model.gain", "1.000000000")
    for key in ["offset.a.positive.x.mm", "offset.a.positive.y.mm",
                "offset.a.negative.x.mm", "offset.a.negative.y.mm",
                "offset.b.positive.x.mm", "offset.b.positive.y.mm",
                "offset.b.negative.x.mm", "offset.b.negative.y.mm"]:
        props.setProperty(key, "0.000")
    props.setProperty("threshold.a.mm", "500.000")
    props.setProperty("threshold.b.mm", "500.000")
    store_properties_atomic(
        props, path, "OpenPnP CoreXY new-machine initialization")
    return ("New-machine CoreXY configuration initialized: X=" +
            x_axis.getId() + ", Y=" + y_axis.getId() +
            "; compensation remains disabled.")


try:
    if not config_file.isFile():
        raise IOError("CoreXY configuration not found: " +
                      config_file.getAbsolutePath())
    initialization = initialize_new_machine_template(config_file)
    if initialization:
        print("[OpenPnP-CoreXY] " + initialization)
    jar_path = agent_jar.getCanonicalPath()
    if jar_path not in sys.path:
        sys.path.append(jar_path)
    from org.openpnp.corexy.agent import CoreXYScriptBootstrap
    status = CoreXYScriptBootstrap.install(
        jar_path,
        config_file.getCanonicalPath(),
    )
    print("[OpenPnP-CoreXY] Startup completed.\n" + status)
except Exception as error:
    print("[OpenPnP-CoreXY] STARTUP FAILED: " + str(error))
    raise
