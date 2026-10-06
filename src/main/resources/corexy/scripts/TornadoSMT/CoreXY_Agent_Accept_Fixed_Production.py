from __future__ import unicode_literals

import sys
from java.io import File, FileInputStream, FileOutputStream
from java.nio.file import (AtomicMoveNotSupportedException, Files,
                           StandardCopyOption)
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
config_file = File(runtime_dir, "config/corexy.properties")
policy_file = File(runtime_dir, "config/calibration-policy.properties")
review_file = File(
    runtime_dir, "diagnostics/latest-legacy-fixed-physical-review.properties")
backups_dir = File(runtime_dir, "backups")

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


def copy_properties(source):
    target = Properties()
    for key in list(source.stringPropertyNames()):
        name = str(key)
        target.setProperty(name, required(source, name))
    return target


def store_properties(props, path, comment):
    stream = FileOutputStream(path)
    try:
        props.store(stream, comment)
    finally:
        stream.close()


def copy_replace(source, target):
    Files.copy(source.toPath(), target.toPath(),
               StandardCopyOption.REPLACE_EXISTING)


def move_replace(source, target):
    try:
        Files.move(source.toPath(), target.toPath(),
                   StandardCopyOption.ATOMIC_MOVE,
                   StandardCopyOption.REPLACE_EXISTING)
    except AtomicMoveNotSupportedException:
        Files.move(source.toPath(), target.toPath(),
                   StandardCopyOption.REPLACE_EXISTING)


def same_float(left, right):
    return abs(float(left) - float(right)) <= 0.000000001


def accept_and_promote():
    machine = Configuration.get().getMachine()
    if machine.isEnabled() or machine.getMotionPlanner().isHomed():
        raise RuntimeError(
            "Disable and unhome the machine before fixed-vector promotion.")
    for path in [config_file, policy_file, review_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())

    review = load_properties(review_file)
    if required(review, "acceptance.policy") != "legacy-fixed-vision-aware-v1":
        raise ValueError("The latest review is not a fixed-vector review.")
    if required(review, "source-physical-validation-state") not in \
            ["passed", "failed-acceptance"]:
        raise ValueError("The physical measurement did not complete.")
    for index in range(1, 7):
        if required(review, "acceptance.check.{0}.passed".format(index)) != \
                "true":
            raise ValueError(
                "A non-overridable integrity or regression check failed: " +
                required(review,
                         "acceptance.check.{0}.description".format(index)))
    system_passed = required(review, "validation.passed") == "true"

    physical_file = File(required(review, "source-physical-validation"))
    if not physical_file.isFile():
        raise IOError("Physical evidence was not found.")
    physical = load_properties(physical_file)
    stamped_physical = File(
        physical_file.getParentFile(),
        "legacy-fixed-physical-validation-" +
        required(physical, "created") + ".properties")
    if stamped_physical.isFile():
        physical_file = stamped_physical
        physical = load_properties(physical_file)
    stamped_review = File(
        review_file.getParentFile(),
        "legacy-fixed-physical-review-" +
        required(review, "created") + ".properties")
    evidence_review_file = (stamped_review if stamped_review.isFile()
                            else review_file)
    gain = float(required(review, "model.gain"))
    if not same_float(required(physical, "model.gain"), gain):
        raise ValueError("Review and physical evidence gains differ.")
    trial_file = File(required(physical, "source-trial"))
    if not trial_file.isFile():
        raise IOError("Validated trial was not found.")
    trial = load_properties(trial_file)
    if required(trial, "mode") != "state-offset" or \
            required(trial, "trial.kind") != "legacy-fixed-vector-physical" or \
            required(trial, "enabled") != "true" or \
            required(trial, "dry-run") != "false":
        raise ValueError("The validated evidence is not a real fixed-vector trial.")
    if not same_float(required(trial, "model.gain"), gain):
        raise ValueError("Trial and physical evidence gains differ.")
    policy = load_properties(policy_file)
    policy_maximum = float(required(policy, "target.absolute-maximum.mm"))
    trial_maximum = float(required(trial, "safety.max-offset.mm"))
    if trial_maximum > policy_maximum + 0.000000001:
        raise ValueError("The trial exceeds the fixed-vector safety policy.")

    before = float(required(review, "baseline.all.rms.mm"))
    after = float(required(review, "physical.all.rms.mm"))
    p95 = float(required(review, "physical.all.p95.mm"))
    maximum = float(required(review, "physical.all.maximum.mm"))
    target = float(required(review, "target.radial-tolerance.mm"))
    guard = float(required(review, "target.maximum-residual-guard.mm"))
    result_note = (
        "The system acceptance target passed.\n"
        "系统自动验收目标已经通过。"
        if system_passed else
        "The absolute performance target did NOT pass.\n"
        "绝对性能目标没有通过；此次授权只适用于本机。")
    message = (
        "Apply fixed-vector compensation / 正式应用固定向量补偿\n\n"
        "Gain / 增益: {0:.0%}\n"
        "All RMS / 总RMS: {1:.6f} -> {2:.6f} mm ({3:.1f}% improvement)\n"
        "P95: {4:.6f} mm; target / 目标: {5:.6f} mm\n"
        "Maximum / 最大值: {6:.6f} mm; guard / 目标防护线: {7:.6f} mm\n\n"
        "The first six integrity/regression checks passed.\n"
        "前六项完整性、改善和局部恶化检查均已通过。\n"
        "{8}\n\n"
        "The current formal configuration will be backed up.\n"
        "No movement occurs now. Restart OpenPnP and Home after applying.\n\n"
        "Type APPLY FIXED {9:.0f} to continue."
    ).format(gain, before, after, 100.0 * (before - after) / before,
             p95, target, maximum, guard, result_note, gain * 100.0)
    answer = JOptionPane.showInputDialog(None, message, "")
    expected = "APPLY FIXED {0:.0f}".format(gain * 100.0)
    if answer is None or str(answer).strip().upper() != expected:
        return

    if not backups_dir.isDirectory() and not backups_dir.mkdirs():
        raise IOError("Cannot create backup directory.")
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    backup = File(backups_dir,
                  "corexy-before-fixed-production-" + stamp + ".properties")
    previous = File(backups_dir, "corexy.previous.properties")
    acceptance_file = File(
        runtime_dir, "diagnostics/fixed-user-acceptance-" + stamp +
        ".properties")
    latest_acceptance = File(
        runtime_dir, "diagnostics/latest-fixed-user-acceptance.properties")
    temp = File(config_file.getParentFile(),
                "corexy.properties.fixed-" + stamp + ".tmp")

    acceptance = Properties()
    acceptance.setProperty("schema.version", "1")
    acceptance_state = ("system-validated" if system_passed else
                        "user-accepted-with-deviation")
    acceptance.setProperty("state", acceptance_state)
    acceptance.setProperty("created", stamp)
    acceptance.setProperty("machine.scope", "local-only")
    acceptance.setProperty("model.gain", "{0:.9f}".format(gain))
    acceptance.setProperty("system.validation.passed",
                           required(review, "validation.passed"))
    acceptance.setProperty("source.review", evidence_review_file.getCanonicalPath())
    acceptance.setProperty("source.physical", physical_file.getCanonicalPath())
    acceptance.setProperty("source.trial", trial_file.getCanonicalPath())
    acceptance.setProperty("accepted.phrase", expected)
    store_properties(acceptance, acceptance_file,
                     "Fixed-vector production acceptance")
    copy_replace(acceptance_file, latest_acceptance)

    production = copy_properties(trial)
    for key in list(production.stringPropertyNames()):
        if str(key).startswith("trial."):
            production.remove(key)
    production.setProperty("production.persistence", "persistent")
    production.setProperty("production.promoted-at", stamp)
    production.setProperty("production.acceptance", acceptance_state)
    production.setProperty("production.source-review",
                           evidence_review_file.getCanonicalPath())
    production.setProperty("production.source-physical-validation",
                           physical_file.getCanonicalPath())
    production.setProperty("production.source-trial", trial_file.getCanonicalPath())
    production.setProperty("production.source-user-acceptance",
                           acceptance_file.getCanonicalPath())
    production.setProperty("production.rollback-file", previous.getCanonicalPath())

    replaced = False
    try:
        store_properties(production, temp,
                         "OpenPnP CoreXY fixed-vector production")
        copy_replace(config_file, backup)
        copy_replace(config_file, previous)
        move_replace(temp, config_file)
        replaced = True
        status = str(CoreXYScriptBootstrap.reload())
        if "mode=state-offset" not in status or "enabled=true" not in status or \
                "dry-run=false" not in status:
            raise RuntimeError("Agent did not accept the fixed production profile.")
    except Exception:
        if replaced and backup.isFile():
            copy_replace(backup, config_file)
            CoreXYScriptBootstrap.reload()
        raise
    finally:
        if temp.isFile():
            temp.delete()

    JOptionPane.showMessageDialog(
        None,
        "Fixed-vector production profile applied.\n"
        "固定向量正式配置已写入。\n\n"
        (("System acceptance passed and was recorded.\n"
          "系统自动验收通过并已记录。\n\n") if system_passed else
         ("System target remains failed; conditional user acceptance was recorded.\n"
          "系统目标仍标记为失败，用户有条件接受已单独记录。\n\n")) +
        "Restart OpenPnP, Home, then perform the production health check.\n"
        "请重启OpenPnP、Home，然后执行生产健康检查。\n\n" + status,
        "CoreXY Fixed Production / 固定向量正式应用",
        JOptionPane.WARNING_MESSAGE)


try:
    accept_and_promote()
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error),
        "CoreXY Fixed Production Error / 固定向量应用错误",
        JOptionPane.ERROR_MESSAGE)
    raise
