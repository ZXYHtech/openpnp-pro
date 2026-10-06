import sys
from java.io import File
from java.nio.file import (AtomicMoveNotSupportedException, Files,
                           StandardCopyOption)
from java.text import SimpleDateFormat
from java.util import Date
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
config_file = File(runtime_dir, "config/corexy.properties")
backups_dir = File(runtime_dir, "backups")
previous_file = File(backups_dir, "corexy.previous.properties")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap


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


def rollback():
    machine = Configuration.get().getMachine()
    if machine.isEnabled() or machine.getMotionPlanner().isHomed():
        raise RuntimeError(
            "Disable and unhome the machine before CoreXY rollback.")
    for path in [config_file, previous_file]:
        if not path.isFile():
            raise IOError("Required file not found: " + path.getAbsolutePath())
    message = (
        "Rollback to the previous CoreXY compensation configuration\n\n"
        "Current:\n{0}\n\nPrevious:\n{1}\n\n"
        "No movement will occur. Restart OpenPnP after rollback.\n\n"
        "Type ROLLBACK COREXY to continue."
    ).format(config_file.getAbsolutePath(), previous_file.getAbsolutePath())
    confirmation = JOptionPane.showInputDialog(None, message, "")
    if confirmation is None or \
            str(confirmation).strip().upper() != "ROLLBACK COREXY":
        return

    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    archive = File(
        backups_dir, "corexy-before-rollback-" + stamp + ".properties")
    temp = File(config_file.getParentFile(),
                "corexy.properties.rollback-" + stamp + ".tmp")
    replaced = False
    try:
        copy_replace(config_file, archive)
        copy_replace(previous_file, temp)
        move_replace(temp, config_file)
        replaced = True
        status = str(CoreXYScriptBootstrap.reload())
    except Exception:
        if replaced and archive.isFile():
            restore_temp = File(
                config_file.getParentFile(),
                "corexy.properties.rollback-restore-" + stamp + ".tmp")
            copy_replace(archive, restore_temp)
            move_replace(restore_temp, config_file)
            CoreXYScriptBootstrap.reload()
        raise
    finally:
        if temp.isFile():
            temp.delete()

    JOptionPane.showMessageDialog(
        None,
        "CoreXY configuration rolled back successfully.\n\n"
        "Restored:\n" + previous_file.getAbsolutePath() +
        "\n\nReplaced configuration archived at:\n" +
        archive.getAbsolutePath() +
        "\n\nRestart OpenPnP before use.\n\n" + status,
        "OpenPnP CoreXY Production Rollback",
        JOptionPane.INFORMATION_MESSAGE)


try:
    rollback()
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "OpenPnP CoreXY Production Rollback Error",
        JOptionPane.ERROR_MESSAGE)
    raise
