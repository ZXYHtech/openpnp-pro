import sys
from jarray import zeros
from java.io import File, FileInputStream, FileOutputStream
from java.security import MessageDigest
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
formal_file = File(runtime_dir, "config/corexy.properties")
trials_dir = File(runtime_dir, "trials")

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


def required(props, key):
    value = props.getProperty(key)
    if value is None or not str(value).strip():
        raise ValueError("Missing property: " + key)
    return str(value).strip()


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


def write_properties(props, path):
    stream = FileOutputStream(path)
    try:
        props.store(stream, "Transient uncompensated Home baseline mode")
    finally:
        stream.close()


try:
    language = (read_ui_language() if "read_ui_language" in globals()
                else "zh_CN")
    machine = Configuration.get().getMachine()
    if machine.isEnabled() or machine.getMotionPlanner().isHomed():
        raise RuntimeError(
            "Disable and unhome the machine before loading Home baseline mode.")
    if not formal_file.isFile():
        raise IOError(
            "Formal CoreXY configuration not found: " +
            formal_file.getAbsolutePath())

    formal = load_properties(formal_file)
    formal_hash = sha256(formal_file)
    if required(formal, "production.persistence") != "persistent" or \
            required(formal, "dry-run") != "false":
        raise ValueError(
            "A persistent production configuration is required.")

    confirm_message = (
        u"Load uncompensated Home baseline mode.\n\n"
        u"CoreXY compensation will be disabled for this OpenPnP process only.\n"
        u"The production compensation file will not be changed.\n"
        u"After loading, Enable and Home before capturing the baseline.\n\n"
        u"Do not run a placement job in this diagnostic mode.\n\nContinue?"
        if language == "en" else
        u"加载未补偿 Home 基线模式。\n\n"
        u"CoreXY 补偿只会在当前 OpenPnP 进程中停用。\n"
        u"正式补偿配置文件不会被修改。\n"
        u"加载后必须重新 Enable 并正常 Home，再采集未补偿基线。\n\n"
        u"诊断模式下不要运行贴片任务。\n\n"
        u"是否继续？")
    answer = JOptionPane.showConfirmDialog(
        None, confirm_message,
        u"Load Uncompensated Baseline Mode" if language == "en" else
        u"加载未补偿基线模式",
        JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.WARNING_MESSAGE)
    if answer != JOptionPane.OK_OPTION:
        raise RuntimeError("Home baseline mode loading was cancelled.")

    trial = Properties()
    trial.putAll(formal)
    for key in list(trial.stringPropertyNames()):
        name = str(key)
        if name.startswith("production.") or name.startswith("trial."):
            trial.remove(key)
    trial.setProperty("enabled", "false")
    trial.setProperty("dry-run", "false")
    trial.setProperty("trial.kind", "home-reference-baseline")
    trial.setProperty("trial.persistence", "process-only")
    trial.setProperty("trial.source-production-config",
                      formal_file.getCanonicalPath())
    trial.setProperty("trial.source-production-config.sha256", formal_hash)
    trial.setProperty("trial.production-revision",
                      required(formal, "production.promoted-at"))
    trial.setProperty("trial.production-gain", required(formal, "model.gain"))
    trial.setProperty(
        "trial.created", SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()))

    if not trials_dir.isDirectory() and not trials_dir.mkdirs():
        raise IOError("Cannot create trials directory.")
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    stamped = File(
        trials_dir, "home-reference-baseline-mode-" + stamp + ".properties")
    latest = File(
        trials_dir, "latest-home-reference-baseline-mode.properties")
    write_properties(trial, stamped)
    write_properties(trial, latest)

    status_text = CoreXYScriptBootstrap.loadTransient(
        stamped.getCanonicalPath())
    status = status_values(status_text)
    integrity = (sha256(formal_file) == formal_hash and
                 status.get("enabled") == "false" and
                 status.get("dry-run") == "false" and
                 File(status.get("config", "")).getCanonicalPath().lower() ==
                 stamped.getCanonicalPath().lower())
    if not integrity:
        CoreXYScriptBootstrap.reload()
        raise RuntimeError(
            "Home baseline mode integrity check failed; formal config restored.")

    result_message = (
        u"Uncompensated Home baseline mode loaded for this process.\n\n"
        u"1. Enable and Home normally.\n"
        u"2. Follow the Tuner status to capture the uncompensated baseline.\n"
        u"3. Do not run a placement job before calibration is complete.\n\n"
        u"Production configuration SHA-256 is unchanged.\nTrial: " +
        stamped.getAbsolutePath() + u"\n\nAgent status:\n" + unicode(status_text)
        if language == "en" else
        u"当前进程已加载未补偿 Home 基线模式。\n\n"
        u"1. Enable 并正常 Home。\n"
        u"2. 按 Tuner 当前状态提示采集未补偿基线。\n"
        u"3. 完成标定前不要运行贴片任务。\n\n"
        u"正式配置 SHA-256 保持不变。\n"
        u"临时配置：" + stamped.getAbsolutePath() + u"\n\nAgent 状态：\n" +
        unicode(status_text))
    JOptionPane.showMessageDialog(
        None, result_message,
        u"OpenPnP CoreXY Uncompensated Baseline Mode" if language == "en" else
        u"OpenPnP CoreXY 未补偿基线模式",
        JOptionPane.WARNING_MESSAGE)
except RuntimeError as error:
    if "cancel" not in str(error).lower():
        JOptionPane.showMessageDialog(
            None, str(error), "CoreXY Home Baseline Mode",
            JOptionPane.ERROR_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Home Baseline Mode Error",
        JOptionPane.ERROR_MESSAGE)
    raise
