import math
import sys
from jarray import array
from java.io import File, FileOutputStream, OutputStreamWriter
from java.lang import Runnable
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import (JDialog, JLabel, JOptionPane, JPanel, JProgressBar,
                         SwingUtilities)
from java.awt import BorderLayout, Dimension
from org.openpnp.model import Configuration
from org.openpnp.util import UiUtils


scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
agent_jar = File(runtime_dir, "lib/openpnp-corexy-agent.jar")
reports_dir = File(runtime_dir, "reports")
diagnostics_dir = File(runtime_dir, "diagnostics")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap
from org.openpnp.corexy.diagnostics import CoreXYVisualDiagnostics


VIRTUAL_DISTANCE_MM = 120.0
DISTANCE_ARRAY = array([VIRTUAL_DISTANCE_MM], 'd')
if "COREXY_SPEED_SEQUENCE" in globals():
    SPEED_FACTORS = list(COREXY_SPEED_SEQUENCE)
else:
    SPEED_FACTORS = [0.20, 0.50, 0.75, 1.00]
REPEATS = 3
SETTLE_MS = 500
STATIONARY_CAPTURES = 7


def ensure_directory(directory):
    if not directory.isDirectory() and not directory.mkdirs():
        raise IOError("Cannot create directory: " + directory.getAbsolutePath())


def vector_distance(left, right):
    return math.hypot(left.getCorrectionXmm() - right.getCorrectionXmm(),
                      left.getCorrectionYmm() - right.getCorrectionYmm())


def maximum_pairwise(results):
    maximum = 0.0
    for first in range(len(results)):
        for second in range(first + 1, len(results)):
            maximum = max(maximum, vector_distance(results[first], results[second]))
    return maximum


def maximum_scatter(results):
    return max([item.getScatterMm() for item in results])


def direction_line(speed, result):
    return (
        "speed {0:.2f} {1}: correction ({2:+.6f}, {3:+.6f}) mm, "
        "magnitude {4:.6f} mm, max scatter {5:.6f} mm"
    ).format(speed, result.getDirection(), result.getCorrectionXmm(),
             result.getCorrectionYmm(), result.getMagnitudeMm(),
             result.getScatterMm())


def sample_lines(lines, result):
    for sample in result.getSamples():
        lines.append(
            "  #{0}: +return ({1:.6f},{2:.6f}) -return ({3:.6f},{4:.6f}) difference ({5:+.6f},{6:+.6f})".format(
                sample.getRepeat(), sample.getPositiveLocation().getX(),
                sample.getPositiveLocation().getY(),
                sample.getNegativeLocation().getX(),
                sample.getNegativeLocation().getY(),
                sample.getCorrectionXmm(), sample.getCorrectionYmm()))


def build_report(results):
    a_results = [item.getAResults().get(0) for item in results]
    b_results = [item.getBResults().get(0) for item in results]
    detection_floor = max([item.getVisionDetectionFloorMm() for item in results])
    noise = max([item.getStationaryVisionNoiseMm() for item in results])
    observed_floor = max(detection_floor, noise * 3.0,
                         maximum_scatter(a_results), maximum_scatter(b_results))
    allowed_variation = observed_floor * 2.0
    a_span = maximum_pairwise(a_results)
    b_span = maximum_pairwise(b_results)
    lines = [
        "OpenPnP CoreXY External Explicit-speed Scan",
        "",
        "No compensation candidate was applied.",
        "Virtual distance: {0:.3f} mm; physical X/Y excursion: {1:.3f} mm".format(
            VIRTUAL_DISTANCE_MM, VIRTUAL_DISTANCE_MM * 0.5),
        "Explicit speed factors: " + ", ".join(
            ["{0:.2f}".format(speed) for speed in SPEED_FACTORS]),
        "The OpenPnP global speed slider does not scale these explicit main moves.",
        "Repeats: {0}; settle: {1} ms".format(REPEATS, SETTLE_MS),
        "Camera-derived detection floor: {0:.6f} mm".format(detection_floor),
        "Maximum stationary vision noise: {0:.6f} mm".format(noise),
        "Observed floor=max(camera floor, 3x noise, max scatter): {0:.6f} mm".format(
            observed_floor),
        "Speed-variation comparison limit=2x observed floor: {0:.6f} mm".format(
            allowed_variation),
        "The OpenPnP configured axis tolerance is not used.",
        "",
        "Measurements",
    ]
    for index, speed in enumerate(SPEED_FACTORS):
        reference = results[index].getDetectedReference()
        lines.append(
            "speed {0:.2f} centered reference: X={1:.6f}, Y={2:.6f}, Z={3:.6f}".format(
                speed, reference.getX(), reference.getY(), reference.getZ()))
        lines.append(direction_line(speed, a_results[index]))
        sample_lines(lines, a_results[index])
        lines.append(direction_line(speed, b_results[index]))
        sample_lines(lines, b_results[index])
    lines.extend([
        "",
        "Speed sensitivity",
        "A maximum pairwise correction-vector change: {0:.6f} mm ({1})".format(
            a_span, "speed-sensitive" if a_span > allowed_variation else "within observed variation"),
        "B maximum pairwise correction-vector change: {0:.6f} mm ({1})".format(
            b_span, "speed-sensitive" if b_span > allowed_variation else "within observed variation"),
        "",
        "A speed-sensitive result means one fixed offset must be validated against the full speed envelope.",
        "It does not authorize speed-dependent compensation or application of a candidate.",
    ])
    return "\n".join(lines), a_results, b_results, observed_floor, a_span, b_span


def write_report(report, stamp):
    ensure_directory(reports_dir)
    report_file = File(reports_dir, "visual-speed-scan-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report_file), "UTF-8")
    try:
        writer.write(report)
    finally:
        writer.close()
    return report_file


def store_properties(results, a_results, b_results, observed_floor,
                     a_span, b_span, stamp):
    ensure_directory(diagnostics_dir)
    props = Properties()
    props.setProperty("schema.version", "1")
    props.setProperty("source", "external-visual-explicit-speed-scan")
    props.setProperty("created", stamp)
    props.setProperty("virtual-distance.mm", "{0:.6f}".format(VIRTUAL_DISTANCE_MM))
    props.setProperty("repeats", str(REPEATS))
    props.setProperty("observed-floor.mm", "{0:.9f}".format(observed_floor))
    props.setProperty("a.maximum-pairwise-change.mm", "{0:.9f}".format(a_span))
    props.setProperty("b.maximum-pairwise-change.mm", "{0:.9f}".format(b_span))
    props.setProperty("speed.count", str(len(SPEED_FACTORS)))
    for index, speed in enumerate(SPEED_FACTORS):
        prefix = "speed.{0}.".format(index + 1)
        props.setProperty(prefix + "factor", "{0:.6f}".format(speed))
        props.setProperty(prefix + "stationary-noise.mm", "{0:.9f}".format(
            results[index].getStationaryVisionNoiseMm()))
        reference = results[index].getDetectedReference()
        props.setProperty(prefix + "reference.x.mm", "{0:.9f}".format(
            reference.getX()))
        props.setProperty(prefix + "reference.y.mm", "{0:.9f}".format(
            reference.getY()))
        props.setProperty(prefix + "reference.z.mm", "{0:.9f}".format(
            reference.getZ()))
        for direction, result in (("a", a_results[index]), ("b", b_results[index])):
            direction_prefix = prefix + direction + "."
            props.setProperty(direction_prefix + "x.mm", "{0:.9f}".format(
                result.getCorrectionXmm()))
            props.setProperty(direction_prefix + "y.mm", "{0:.9f}".format(
                result.getCorrectionYmm()))
            props.setProperty(direction_prefix + "scatter.mm", "{0:.9f}".format(
                result.getScatterMm()))
            props.setProperty(direction_prefix + "sample.count", str(len(result.getSamples())))
            for sample_index, sample in enumerate(result.getSamples()):
                sample_prefix = direction_prefix + "sample.{0}.".format(sample_index + 1)
                props.setProperty(sample_prefix + "positive.x.mm", "{0:.9f}".format(
                    sample.getPositiveLocation().getX()))
                props.setProperty(sample_prefix + "positive.y.mm", "{0:.9f}".format(
                    sample.getPositiveLocation().getY()))
                props.setProperty(sample_prefix + "negative.x.mm", "{0:.9f}".format(
                    sample.getNegativeLocation().getX()))
                props.setProperty(sample_prefix + "negative.y.mm", "{0:.9f}".format(
                    sample.getNegativeLocation().getY()))

    stamped = File(diagnostics_dir, "speed-scan-" + stamp + ".properties")
    latest = File(diagnostics_dir, "latest-speed-scan.properties")
    for output_file in (stamped, latest):
        stream = FileOutputStream(output_file)
        try:
            props.store(stream, "OpenPnP CoreXY explicit-speed diagnostic")
        finally:
            stream.close()
    return stamped


class ProgressWindow(object):
    def __init__(self):
        self.phase = 0
        self.dialog = JDialog(None, "CoreXY Explicit-speed Scan", False)
        self.label = JLabel("Preparing explicit-speed scan...")
        self.bar = JProgressBar(0, 100)
        self.bar.setStringPainted(True)
        panel = JPanel(BorderLayout(8, 8))
        panel.add(self.label, BorderLayout.NORTH)
        panel.add(self.bar, BorderLayout.CENTER)
        panel.setPreferredSize(Dimension(560, 76))
        self.dialog.getContentPane().add(panel)
        self.dialog.pack()
        self.dialog.setLocationRelativeTo(None)
        self.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE)

    def show(self):
        self.dialog.setVisible(True)

    def close(self):
        self.dialog.dispose()

    def set_phase(self, phase):
        self.phase = phase

    def update(self, stage, completed, total):
        combined_total = total * len(SPEED_FACTORS)
        combined_completed = self.phase * total + completed
        speed = SPEED_FACTORS[self.phase]
        stage_name = str(stage).split("|")[0]
        labels = {
            "reference": "Centering reference",
            "vision-noise": "Measuring vision noise",
            "distance-a": "Measuring A=X+Y",
            "distance-b": "Measuring B=X-Y",
            "return-reference": "Returning to reference",
            "complete": "Speed phase complete",
            "failed": "Measurement failed",
        }
        label = "Explicit speed {0:.2f}: {1}".format(
            speed, labels.get(stage_name, stage_name))
        percent = int(round(100.0 * combined_completed / max(1, combined_total)))

        class UpdateUi(Runnable):
            def run(inner_self):
                self.label.setText(label + "  {0}/{1}".format(
                    combined_completed, combined_total))
                self.bar.setValue(percent)

        SwingUtilities.invokeLater(UpdateUi())


class ProgressListener(CoreXYVisualDiagnostics.ProgressListener):
    def __init__(self, window):
        self.window = window

    def onProgress(self, stage, completed, total):
        self.window.update(stage, completed, total)


status = str(CoreXYScriptBootstrap.status())
if "enabled=true" in status:
    raise RuntimeError("Disable CoreXY compensation before the explicit-speed scan.")

message = (
    "This uncompensated scan tests explicit speed factors 0.20, 0.50, 0.75 and 1.00.\n\n"
    "Virtual A/B distance: {0:.1f} mm; physical X/Y excursion: {1:.1f} mm. "
    "Each speed measures A and B with {2} return pairs.\n\n"
    "The top global speed slider is not used for these main moves. The final phase is a real "
    "100% nominal-speed movement request. No candidate will be applied.\n\n"
    "Raise the head, clear the full movement area, verify emergency stop, then continue."
).format(VIRTUAL_DISTANCE_MM, VIRTUAL_DISTANCE_MM * 0.5, REPEATS)

answer = JOptionPane.showConfirmDialog(
    None, message, "CoreXY External Explicit-speed Scan",
    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)

if answer == JOptionPane.OK_OPTION:
    progress_window = ProgressWindow()
    progress_window.show()
    listener = ProgressListener(progress_window)

    def run_scan():
        try:
            results = []
            for index, speed in enumerate(SPEED_FACTORS):
                progress_window.set_phase(index)
                results.append(CoreXYVisualDiagnostics.measureDistanceScan(
                    DISTANCE_ARRAY, REPEATS, speed, SETTLE_MS,
                    STATIONARY_CAPTURES, listener))
            stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
            report, a_results, b_results, observed_floor, a_span, b_span = \
                build_report(results)
            report_file = write_report(report, stamp)
            data_file = store_properties(results, a_results, b_results,
                                         observed_floor, a_span, b_span, stamp)
            limit = observed_floor * 2.0
            summary = (
                "A maximum speed change: {0:.6f} mm ({1})\n"
                "B maximum speed change: {2:.6f} mm ({3})\n"
                "Comparison limit: {4:.6f} mm\n\n"
                "No candidate has been applied.\n\nReport: {5}\nData: {6}"
            ).format(a_span, "sensitive" if a_span > limit else "within variation",
                     b_span, "sensitive" if b_span > limit else "within variation",
                     limit, report_file.getAbsolutePath(), data_file.getAbsolutePath())

            class ShowResult(Runnable):
                def run(inner_self):
                    progress_window.close()
                    JOptionPane.showMessageDialog(
                        None, summary, "CoreXY Explicit-speed Scan Result",
                        JOptionPane.INFORMATION_MESSAGE)

            SwingUtilities.invokeLater(ShowResult())
        except Exception as error:
            text = str(error)

            class ShowError(Runnable):
                def run(inner_self):
                    progress_window.close()
                    JOptionPane.showMessageDialog(
                        None, text, "CoreXY Explicit-speed Scan Error",
                        JOptionPane.ERROR_MESSAGE)

            SwingUtilities.invokeLater(ShowError())

    UiUtils.submitUiMachineTask(run_scan)
