import sys
from jarray import zeros
from java.io import File, FileInputStream, FileOutputStream
from java.security import MessageDigest
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration, LengthUnit


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
formal_file = File(runtime_dir, "config/corexy.properties")
policy_file = File(runtime_dir, "config/calibration-policy.properties")
sessions_dir = File(runtime_dir, "sessions")
current_file = File(sessions_dir, "current-session.properties")
pending_profile_file = File(sessions_dir, "pending-fixed-calibration.properties")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap


def load_properties(path):
    props = Properties()
    stream = FileInputStream(path)
    try:
        props.load(stream)
    finally:
        stream.close()
    return props


def write_properties(props, path):
    stream = FileOutputStream(path)
    try:
        props.store(stream, "OpenPnP CoreXY fresh dual-anchor session")
    finally:
        stream.close()


def sha256(path):
    digest = MessageDigest.getInstance("SHA-256")
    stream = FileInputStream(path)
    try:
        buffer = zeros(8192, 'b')
        while True:
            count = stream.read(buffer)
            if count < 0:
                break
            digest.update(buffer, 0, count)
    finally:
        stream.close()
    return "".join(["{0:02x}".format((value + 256) % 256)
                    for value in digest.digest()])


def status_values(text):
    values = {}
    for line in str(text).splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def set_float(props, key, value):
    props.setProperty(key, "{0:.9f}".format(float(value)))


try:
    language = (read_ui_language() if "read_ui_language" in globals()
                else "zh_CN")
    for path in [formal_file, policy_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    status = status_values(CoreXYScriptBootstrap.status())
    if status.get("enabled") not in ["true", "false"] or \
            status.get("dry-run") != "false" or status.get("mode") not in \
            ["distance-speed-state-offset", "state-offset"]:
        raise RuntimeError("Restore the persistent formal CoreXY configuration first.")
    if File(status.get("config", "")).getCanonicalPath().lower() != \
            formal_file.getCanonicalPath().lower():
        raise RuntimeError("The active CoreXY configuration is not the formal file.")
    machine = Configuration.get().getMachine()
    if machine.isEnabled() or machine.isHomed():
        raise RuntimeError("Disable the machine and leave it unhomed before starting a session.")
    formal = load_properties(formal_file)
    fixed_formal = (status.get("mode") == "state-offset" and
                    formal.getProperty("model.kind", "") ==
                    "FRESH_FIXED_VECTOR_V1" and
                    formal.getProperty("production.acceptance", "") in
                    ["system-validated", "user-accepted-with-deviation",
                     "user-manual-ui", "direct-candidate-ui",
                     "bootstrap-new-machine"])
    surface_formal = status.get("mode") == "distance-speed-state-offset"
    if formal.getProperty("production.persistence", "") != "persistent" or \
            not (fixed_formal or surface_formal):
        raise RuntimeError("The formal CoreXY configuration is not a supported persistent production profile.")
    policy = load_properties(policy_file)
    confirm_message = (
        u"Start a new same-session dual-reference measurement.\n\n"
        u"All scans will be bound to the current production configuration, "
        u"policy, Primary Fiducial and Home point.\n"
        u"Older measurements remain historical and are not mixed into this candidate.\n"
        u"This step does not change the production compensation configuration.\n\n"
        u"Continue?" if language == "en" else
        u"开始新的同期双基准测量 Session。\n\n"
        u"本次扫描将绑定当前正式配置、策略、主要标定点和 Home 点。\n"
        u"旧测量仅作为历史记录，不会混入本次候选。\n"
        u"此步骤不会修改正式补偿配置。\n\n"
        u"是否继续？")
    answer = JOptionPane.showConfirmDialog(
        None, confirm_message,
        u"Start New CoreXY Calibration" if language == "en" else
        u"开始新的 CoreXY 标定",
        JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.QUESTION_MESSAGE)
    if answer != JOptionPane.OK_OPTION:
        raise RuntimeError("Fresh session cancelled.")

    if not sessions_dir.isDirectory() and not sessions_dir.mkdirs():
        raise IOError("Cannot create sessions directory.")
    session_id = "fresh-dual-anchor-" + \
        SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    session_dir = File(sessions_dir, session_id)
    if not session_dir.mkdirs():
        raise IOError("Cannot create session directory: " +
                      session_dir.getAbsolutePath())
    head = machine.getDefaultHead()
    primary = head.getCalibrationPrimaryFiducialLocation().convertToUnits(
        LengthUnit.Millimeters)
    home = head.getHomingFiducialLocation().convertToUnits(
        LengthUnit.Millimeters)
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("session.id", session_id)
    props.setProperty("session.state", "collecting-formal-primary")
    props.setProperty("session.directory", session_dir.getCanonicalPath())
    props.setProperty("created", SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()))
    props.setProperty("formal.path", formal_file.getCanonicalPath())
    props.setProperty("formal.sha256", sha256(formal_file))
    props.setProperty("formal.production-revision",
                      formal.getProperty("production.promoted-at", ""))
    props.setProperty("policy.path", policy_file.getCanonicalPath())
    props.setProperty("policy.sha256", sha256(policy_file))
    props.setProperty("target.radial-tolerance.mm",
                      policy.getProperty("target.radial-tolerance.mm", "0.050000000"))
    if pending_profile_file.isFile():
        pending = load_properties(pending_profile_file)
        for key in ["calibration.distances.mm", "calibration.speeds",
                    "calibration.repeats", "target.radial-tolerance.mm",
                    "target.minimum-trusted-displacement.mm"]:
            value = pending.getProperty(key)
            if value is not None and str(value).strip():
                props.setProperty(key, str(value).strip())
    set_float(props, "primary.x.mm", primary.getX())
    set_float(props, "primary.y.mm", primary.getY())
    set_float(props, "home.x.mm", home.getX())
    set_float(props, "home.y.mm", home.getY())
    write_properties(props, File(session_dir, "manifest.properties"))
    write_properties(props, current_file)
    result_message = (
        u"New same-session data set created.\n\nSession: " + session_id +
        u"\nMeasurement profile: distances " +
        props.getProperty("calibration.distances.mm", "default") +
        u" mm; speeds " + props.getProperty("calibration.speeds", "default") +
        u"; repeats " + props.getProperty("calibration.repeats", "default") +
        u"\nNext: load uncompensated mode, then Enable, Home and capture the Primary baseline.\n\n" +
        session_dir.getAbsolutePath() if language == "en" else
        u"新的同期 Session 已建立。\n\nSession：" + session_id +
        u"\n测量参数：距离 " +
        props.getProperty("calibration.distances.mm", "default") +
        u" mm；速度 " + props.getProperty("calibration.speeds", "default") +
        u"；每组重复 " + props.getProperty("calibration.repeats", "default") +
        u" 次\n下一步：加载未补偿模式，然后 Enable、Home 并采集主要标定点基线。\n\n" +
        session_dir.getAbsolutePath())
    JOptionPane.showMessageDialog(
        None, result_message,
        u"CoreXY Fresh Session" if language == "en" else
        u"CoreXY 新标定 Session", JOptionPane.INFORMATION_MESSAGE)
except RuntimeError as error:
    if "cancel" not in str(error).lower():
        JOptionPane.showMessageDialog(None, str(error), "CoreXY Fresh Session",
                                      JOptionPane.ERROR_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(None, str(error), "CoreXY Fresh Session Error",
                                  JOptionPane.ERROR_MESSAGE)
    raise
