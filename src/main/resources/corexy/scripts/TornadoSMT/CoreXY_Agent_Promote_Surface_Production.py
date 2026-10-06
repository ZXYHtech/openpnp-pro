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
diagnostics_dir = File(runtime_dir, "diagnostics")
backups_dir = File(runtime_dir, "backups")
latest_review_file = File(
    diagnostics_dir, "latest-auto-calibration-physical-review.properties")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap


def ensure_directory(directory):
    if not directory.isDirectory() and not directory.mkdirs():
        raise IOError("Cannot create directory: " + directory.getAbsolutePath())


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
        key = str(key)
        target.setProperty(key, required(source, key))
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


def same_float(left, right, tolerance=0.000000001):
    return abs(float(left) - float(right)) <= tolerance


def verify_review_matches_physical(review, physical):
    for key in [
            "model.gain",
            "baseline.all.rms.mm", "baseline.all.p95.mm",
            "baseline.all.maximum.mm",
            "baseline.a.rms.mm", "baseline.a.p95.mm",
            "baseline.a.maximum.mm",
            "baseline.b.rms.mm", "baseline.b.p95.mm",
            "baseline.b.maximum.mm",
            "physical.all.rms.mm", "physical.all.p95.mm",
            "physical.all.maximum.mm",
            "physical.a.rms.mm", "physical.a.p95.mm",
            "physical.a.maximum.mm",
            "physical.b.rms.mm", "physical.b.p95.mm",
            "physical.b.maximum.mm"]:
        if not same_float(required(review, key), required(physical, key)):
            raise ValueError(
                "Review and physical measurement differ at " + key + ".")


def resolve_stamped_physical(latest_physical, physical):
    stamp = required(physical, "created")
    stamped = File(
        diagnostics_dir,
        "auto-calibration-physical-validation-" + stamp + ".properties")
    return stamped if stamped.isFile() else latest_physical


def resolve_stamped_review(review):
    stamp = required(review, "created")
    stamped = File(
        diagnostics_dir,
        "auto-calibration-physical-review-" + stamp + ".properties")
    return stamped if stamped.isFile() else latest_review_file


def promote():
    machine = Configuration.get().getMachine()
    if machine.isEnabled() or machine.getMotionPlanner().isHomed():
        raise RuntimeError(
            "Disable and unhome the machine before production promotion.")
    for path in [config_file, latest_review_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())

    review = load_properties(latest_review_file)
    if required(review, "state") != "passed" or \
            required(review, "validation.passed") != "true":
        raise ValueError("The latest physical validation review did not pass.")
    if required(review, "acceptance.policy") != "vision-aware-v2":
        raise ValueError("Unsupported physical validation review policy.")
    gain = float(required(review, "model.gain"))
    if gain <= 0.0 or gain > 1.0:
        raise ValueError("The validated gain is outside (0, 1].")

    latest_physical = File(required(
        review, "source-physical-validation"))
    if not latest_physical.isFile():
        raise IOError("Physical validation source was not found: " +
                      latest_physical.getAbsolutePath())
    latest_physical_props = load_properties(latest_physical)
    physical_file = resolve_stamped_physical(
        latest_physical, latest_physical_props)
    physical = load_properties(physical_file)
    if required(physical, "compensation.applied") != "true":
        raise ValueError("The source validation did not apply compensation.")
    if required(physical, "state") not in ["passed", "failed-acceptance"]:
        raise ValueError("The source physical validation did not complete.")
    verify_review_matches_physical(review, physical)

    trial_file = File(required(physical, "source-trial"))
    if not trial_file.isFile():
        raise IOError("Validated physical trial was not found: " +
                      trial_file.getAbsolutePath())
    trial = load_properties(trial_file)
    if required(trial, "schema.version") != "3" or \
            required(trial, "mode") != "distance-speed-state-offset":
        raise ValueError("The validated trial is not a surface configuration.")
    if required(trial, "enabled") != "true" or \
            required(trial, "dry-run") != "false":
        raise ValueError("The validated trial was not a real physical trial.")
    if not same_float(required(trial, "model.gain"), gain):
        raise ValueError("Trial and physical review gains do not match.")
    if float(required(trial, "safety.max-offset.mm")) > 0.075000001:
        raise ValueError("Validated trial exceeds the 0.075 mm safety limit.")

    candidate_file = File(required(trial, "trial.source-candidate"))
    if not candidate_file.isFile():
        raise IOError("Fitted source candidate was not found: " +
                      candidate_file.getAbsolutePath())
    candidate = load_properties(candidate_file)
    if required(candidate, "state") != "fitted-not-applied":
        raise ValueError("The fitted source candidate is not in a valid state.")
    candidate_index = int(required(candidate, "recommended-candidate.index"))
    candidate_prefix = "candidate.{0}".format(candidate_index)
    if candidate_index <= 0 or \
            required(candidate, candidate_prefix + ".eligible") != "true":
        raise ValueError("The fitted source candidate is not eligible.")
    if required(candidate, candidate_prefix + ".policy-eligible") != "true":
        raise ValueError(
            "The fitted source candidate does not meet the user residual target.")
    if not same_float(
            required(candidate, candidate_prefix + ".gain"), gain):
        raise ValueError("Candidate and validated trial gains do not match.")

    all_before = float(required(review, "baseline.all.rms.mm"))
    all_after = float(required(review, "physical.all.rms.mm"))
    b_before = float(required(review, "baseline.b.rms.mm"))
    b_after = float(required(review, "physical.b.rms.mm"))
    message = (
        "Promote the validated CoreXY surface to persistent production use\n\n"
        "Model gain: {0:.0%}\n"
        "All RMS: {1:.6f} -> {2:.6f} mm ({3:.1f}% improvement)\n"
        "B RMS: {4:.6f} -> {5:.6f} mm ({6:.1f}% improvement)\n"
        "Maximum combined offset: {7:.3f} mm\n"
        "Validation policy: vision-aware-v2\n\n"
        "The current formal configuration will be backed up.\n"
        "The new configuration will persist across OpenPnP restarts.\n"
        "No movement will occur during promotion.\n"
        "Restart OpenPnP after promotion, then Home before use.\n\n"
        "Type PROMOTE {8:.0f} to continue."
    ).format(
        gain, all_before, all_after,
        100.0 * (all_before - all_after) / all_before,
        b_before, b_after, 100.0 * (b_before - b_after) / b_before,
        float(required(trial, "safety.max-offset.mm")), gain * 100.0)
    confirmation = JOptionPane.showInputDialog(None, message, "")
    expected = "PROMOTE {0:.0f}".format(gain * 100.0)
    if confirmation is None or \
            str(confirmation).strip().upper() != expected:
        return

    ensure_directory(backups_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    backup = File(
        backups_dir, "corexy-before-production-" + stamp + ".properties")
    previous = File(backups_dir, "corexy.previous.properties")
    temp = File(config_file.getParentFile(),
                "corexy.properties.promote-" + stamp + ".tmp")
    review_source = resolve_stamped_review(review)
    production = copy_properties(trial)
    production.setProperty("enabled", "true")
    production.setProperty("dry-run", "false")
    production.setProperty("production.persistence", "persistent")
    production.setProperty("production.promoted-at", stamp)
    production.setProperty(
        "production.source-review", review_source.getCanonicalPath())
    production.setProperty(
        "production.source-physical-validation",
        physical_file.getCanonicalPath())
    production.setProperty(
        "production.source-trial", trial_file.getCanonicalPath())
    production.setProperty(
        "production.rollback-file", previous.getCanonicalPath())
    production.remove("trial.persistence")

    replaced = False
    try:
        store_properties(
            production, temp, "OpenPnP CoreXY validated production surface")
        copy_replace(config_file, backup)
        copy_replace(config_file, previous)
        move_replace(temp, config_file)
        replaced = True
        status = str(CoreXYScriptBootstrap.reload())
        if "mode=distance-speed-state-offset" not in status or \
                "enabled=true" not in status or "dry-run=false" not in status:
            raise RuntimeError("Agent did not accept the production surface.")

        record = Properties()
        record.setProperty("schema.version", "1")
        record.setProperty("state", "promoted")
        record.setProperty("created", stamp)
        record.setProperty("gain", "{0:.9f}".format(gain))
        record.setProperty("formal-config", config_file.getCanonicalPath())
        record.setProperty("backup", backup.getCanonicalPath())
        record.setProperty("rollback", previous.getCanonicalPath())
        record.setProperty(
            "source-review", review_source.getCanonicalPath())
        record.setProperty(
            "source-physical-validation", physical_file.getCanonicalPath())
        record_file = File(
            backups_dir, "production-promotion-" + stamp + ".properties")
        store_properties(record, record_file,
                         "OpenPnP CoreXY production promotion record")
    except Exception:
        if replaced and backup.isFile():
            restore_temp = File(
                config_file.getParentFile(),
                "corexy.properties.restore-" + stamp + ".tmp")
            copy_replace(backup, restore_temp)
            move_replace(restore_temp, config_file)
            CoreXYScriptBootstrap.reload()
        raise
    finally:
        if temp.isFile():
            temp.delete()

    JOptionPane.showMessageDialog(
        None,
        "Validated {0:.0%} CoreXY surface promoted successfully.\n\n".format(gain) +
        "Persistent configuration:\n" + config_file.getAbsolutePath() +
        "\n\nPrevious configuration:\n" + previous.getAbsolutePath() +
        "\n\nTimestamped backup:\n" + backup.getAbsolutePath() +
        "\n\nRestart OpenPnP, Home the machine, then check Agent Status.\n\n" +
        status,
        "OpenPnP CoreXY Production Promotion",
        JOptionPane.WARNING_MESSAGE)


try:
    promote()
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "OpenPnP CoreXY Production Promotion Error",
        JOptionPane.ERROR_MESSAGE)
    raise
