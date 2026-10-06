import sys
from java.io import File, FileOutputStream, OutputStreamWriter
from java.lang import Runnable
from java.text import SimpleDateFormat
from java.util import Date
from javax.swing import (JDialog, JLabel, JOptionPane, JPanel, JProgressBar,
                         SwingUtilities)
from java.awt import BorderLayout, Dimension
from org.openpnp.model import Configuration
from org.openpnp.util import UiUtils


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
reports_dir = File(runtime_dir, "reports")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap
from org.openpnp.corexy.diagnostics import CoreXYVisualDiagnostics


DISTANCE_MM = 30.0
REPEATS = 3
SPEED_FACTOR = 0.20
SETTLE_MS = 500
STATIONARY_CAPTURES = 7


def location_text(location):
    return "X={0:.6f}, Y={1:.6f}, Z={2:.6f}".format(
        location.getX(), location.getY(), location.getZ())


def direction_lines(result):
    lines = [
        "{0}: correction ({1:+.6f}, {2:+.6f}) mm, magnitude {3:.6f} mm, scatter {4:.6f} mm".format(
            result.getDirection(), result.getCorrectionXmm(),
            result.getCorrectionYmm(), result.getMagnitudeMm(),
            result.getScatterMm())
    ]
    for sample in result.getSamples():
        lines.append(
            "  #{0}: +return ({1:.6f}, {2:.6f}), -return ({3:.6f}, {4:.6f}), difference ({5:+.6f}, {6:+.6f}) mm".format(
                sample.getRepeat(), sample.getPositiveLocation().getX(),
                sample.getPositiveLocation().getY(),
                sample.getNegativeLocation().getX(),
                sample.getNegativeLocation().getY(),
                sample.getCorrectionXmm(), sample.getCorrectionYmm()))
    return lines


def build_report(result):
    lines = [
        "OpenPnP CoreXY External Visual Baseline",
        "",
        "This is an uncompensated result. No candidate was written or enabled.",
        "Virtual distance: {0:.3f} mm; physical X/Y excursion: {1:.3f} mm".format(
            DISTANCE_MM, DISTANCE_MM * 0.5),
        "Repeats: {0}; speed factor: {1:.2f}; settle: {2} ms".format(
            REPEATS, SPEED_FACTOR, SETTLE_MS),
        "Configured Primary Fiducial: " + location_text(result.getConfiguredReference()),
        "Vision-centered reference: " + location_text(result.getDetectedReference()),
        "Feature diameter: {0:.6f} mm".format(result.getFiducialDiameterMm()),
        "Stationary vision noise: {0:.6f} mm".format(
            result.getStationaryVisionNoiseMm()),
        "",
        "Each correction is the + motor-state return minus the - motor-state return.",
        "It is the candidate offset for the + state when the - state is the zero reference.",
        "",
    ]
    lines.extend(direction_lines(result.getAResult()))
    lines.append("")
    lines.extend(direction_lines(result.getBResult()))
    return "\n".join(lines)


def write_report(report):
    if not reports_dir.isDirectory() and not reports_dir.mkdirs():
        raise IOError("Cannot create report directory: " + reports_dir.getAbsolutePath())
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    report_file = File(reports_dir, "visual-baseline-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report_file), "UTF-8")
    try:
        writer.write(report)
    finally:
        writer.close()
    return report_file


class ProgressWindow(object):
    def __init__(self):
        self.dialog = JDialog(None, "CoreXY Visual Baseline", False)
        self.label = JLabel("Preparing visual diagnostics...")
        self.bar = JProgressBar(0, 100)
        self.bar.setStringPainted(True)
        panel = JPanel(BorderLayout(8, 8))
        panel.add(self.label, BorderLayout.NORTH)
        panel.add(self.bar, BorderLayout.CENTER)
        panel.setPreferredSize(Dimension(480, 76))
        self.dialog.getContentPane().add(panel)
        self.dialog.pack()
        self.dialog.setLocationRelativeTo(None)
        self.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE)

    def show(self):
        self.dialog.setVisible(True)

    def close(self):
        self.dialog.dispose()

    def update(self, stage, completed, total):
        labels = {
            "reference": "Centering on the Primary Fiducial",
            "vision-noise": "Measuring stationary vision noise",
            "direction-a": "Measuring A=X+Y return difference",
            "direction-b": "Measuring B=X-Y return difference",
            "return-reference": "Returning to the Primary Fiducial",
            "complete": "Measurement complete",
            "failed": "Measurement failed",
        }
        percent = int(round(100.0 * completed / max(1, total)))

        class UpdateUi(Runnable):
            def run(inner_self):
                self.label.setText(labels.get(str(stage), str(stage)) +
                                   "  {0}/{1}".format(completed, total))
                self.bar.setValue(percent)

        SwingUtilities.invokeLater(UpdateUi())


class ProgressListener(CoreXYVisualDiagnostics.ProgressListener):
    def __init__(self, window):
        self.window = window

    def onProgress(self, stage, completed, total):
        self.window.update(stage, completed, total)


status = str(CoreXYScriptBootstrap.status())
if "enabled=true" in status:
    raise RuntimeError(
        "Disable CoreXY compensation before the uncompensated visual baseline test.")

message = (
    "This test verifies the official OpenPnP vision path through the external CoreXY extension.\n\n"
    "A and B virtual distance: {0:.1f} mm\n"
    "Physical excursion on X/Y: {1:.1f} mm\n"
    "Repeats: {2}; speed factor: {3:.2f}\n\n"
    "Real compensation must remain disabled. X/Y stock backlash is temporarily disabled "
    "and always restored. Home, Primary Fiducial, camera mapping, reachability and a 1 mm "
    "soft-limit margin are checked before movement.\n\n"
    "Raise the head, clear the path, and keep emergency stop ready."
).format(DISTANCE_MM, DISTANCE_MM * 0.5, REPEATS, SPEED_FACTOR)

answer = JOptionPane.showConfirmDialog(
    None, message, "CoreXY External Visual Baseline",
    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)

if answer == JOptionPane.OK_OPTION:
    progress_window = ProgressWindow()
    progress_window.show()
    listener = ProgressListener(progress_window)

    def run_test():
        try:
            result = CoreXYVisualDiagnostics.measureBaseline(
                DISTANCE_MM, REPEATS, SPEED_FACTOR, SETTLE_MS,
                STATIONARY_CAPTURES, listener)
            report = build_report(result)
            report_file = write_report(report)

            class ShowResult(Runnable):
                def run(inner_self):
                    progress_window.close()
                    JOptionPane.showMessageDialog(
                        None, report + "\n\nSaved: " + report_file.getAbsolutePath(),
                        "CoreXY Visual Baseline Result",
                        JOptionPane.INFORMATION_MESSAGE)

            SwingUtilities.invokeLater(ShowResult())
        except Exception as error:
            text = str(error)

            class ShowError(Runnable):
                def run(inner_self):
                    progress_window.close()
                    JOptionPane.showMessageDialog(
                        None, text, "CoreXY Visual Baseline Error",
                        JOptionPane.ERROR_MESSAGE)

            SwingUtilities.invokeLater(ShowError())

    UiUtils.submitUiMachineTask(run_test)
