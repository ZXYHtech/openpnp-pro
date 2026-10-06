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
candidates_dir = File(runtime_dir, "candidates")

jar_path = agent_jar.getCanonicalPath()
if jar_path not in sys.path:
    sys.path.append(jar_path)

from org.openpnp.corexy.agent import CoreXYScriptBootstrap
from org.openpnp.corexy.diagnostics import CoreXYVisualDiagnostics


if "COREXY_DISTANCE_SEQUENCE" in globals():
    DISTANCES_MM = list(COREXY_DISTANCE_SEQUENCE)
else:
    DISTANCES_MM = [5.0, 10.0, 15.0, 20.0, 30.0, 60.0, 120.0]
DISTANCE_ARRAY = array(DISTANCES_MM, 'd')
REPEATS = 3
SPEED_FACTOR = float(COREXY_EXPLICIT_SPEED) \
    if "COREXY_EXPLICIT_SPEED" in globals() else 0.20
SETTLE_MS = 500
STATIONARY_CAPTURES = 7
GENERATE_CANDIDATES = bool(COREXY_GENERATE_CANDIDATES) \
    if "COREXY_GENERATE_CANDIDATES" in globals() else True
SCAN_TITLE = str(COREXY_SCAN_TITLE) \
    if "COREXY_SCAN_TITLE" in globals() else "CoreXY Multi-distance Visual Scan"
REPORT_STEM = str(COREXY_REPORT_STEM) \
    if "COREXY_REPORT_STEM" in globals() else "visual-distance-scan"


def ensure_directory(directory):
    if not directory.isDirectory() and not directory.mkdirs():
        raise IOError("Cannot create directory: " + directory.getAbsolutePath())


def direction_report(lines, result):
    lines.append(
        "{0} {1:7.1f}  correction ({2:+.6f}, {3:+.6f}) mm  magnitude {4:.6f}  scatter {5:.6f}".format(
            result.getDirection(), result.getVirtualDistanceMm(),
            result.getCorrectionXmm(), result.getCorrectionYmm(),
            result.getMagnitudeMm(), result.getScatterMm()))
    for sample in result.getSamples():
        lines.append(
            "  #{0}: +return ({1:.6f},{2:.6f}) -return ({3:.6f},{4:.6f}) difference ({5:+.6f},{6:+.6f})".format(
                sample.getRepeat(), sample.getPositiveLocation().getX(),
                sample.getPositiveLocation().getY(),
                sample.getNegativeLocation().getX(),
                sample.getNegativeLocation().getY(),
                sample.getCorrectionXmm(), sample.getCorrectionYmm()))


def fit_report(fit):
    return (
        "{0}: threshold {1:.3f} mm, +state correction ({2:+.6f}, {3:+.6f}) mm, "
        "baseline worst {4:.6f} mm, modeled worst {5:.6f} mm"
    ).format(fit.getDirection(), fit.getThresholdMm(),
             fit.getCorrectionXmm(), fit.getCorrectionYmm(),
             fit.getBaselineWorstMm(), fit.getExpectedWorstMm())


def build_report(result):
    diagnostic_floor = max(result.getVisionDetectionFloorMm(),
                           result.getStationaryVisionNoiseMm() * 3.0)
    lines = [
        "OpenPnP CoreXY External " + SCAN_TITLE,
        "",
        "No compensation candidate was applied during this scan.",
        "Distances: " + ", ".join(["{0:g}".format(value) for value in DISTANCES_MM]),
        "Repeats: {0}; speed factor: {1:.2f}; settle: {2} ms".format(
            REPEATS, SPEED_FACTOR, SETTLE_MS),
        "Configured Primary Fiducial: X={0:.6f}, Y={1:.6f}, Z={2:.6f}".format(
            result.getConfiguredReference().getX(), result.getConfiguredReference().getY(),
            result.getConfiguredReference().getZ()),
        "Vision-centered reference: X={0:.6f}, Y={1:.6f}, Z={2:.6f}".format(
            result.getDetectedReference().getX(), result.getDetectedReference().getY(),
            result.getDetectedReference().getZ()),
        "Stationary vision noise: {0:.6f} mm".format(
            result.getStationaryVisionNoiseMm()),
        "Camera-derived detection floor: {0:.6f} mm".format(
            result.getVisionDetectionFloorMm()),
        "Diagnostic floor=max(camera floor, 3x stationary noise): {0:.6f} mm".format(
            diagnostic_floor),
        "The OpenPnP configured axis tolerance is not used.",
        "Scatter is the maximum radial deviation from the median correction vector.",
        "",
        "Distance measurements",
    ]
    for a_result, b_result in zip(result.getAResults(), result.getBResults()):
        direction_report(lines, a_result)
        direction_report(lines, b_result)
    lines.extend(["", "Step-model fits", fit_report(result.getAFit()),
                  fit_report(result.getBFit())])
    if GENERATE_CANDIDATES:
        lines.extend(["", "Generated candidates"])
        for candidate in result.getCandidates():
            lines.append(
                "scale {0:.2f}: A({1:+.6f},{2:+.6f}) B({3:+.6f},{4:+.6f}) expected worst {5:.6f} mm".format(
                    candidate.getScale(), candidate.getACorrectionXmm(),
                    candidate.getACorrectionYmm(), candidate.getBCorrectionXmm(),
                    candidate.getBCorrectionYmm(), candidate.getExpectedWorstMm()))
        lines.extend([
            "",
            "Candidates are model predictions only. They must pass randomized visual validation before application.",
            "The fit requires at least two active distances, which prevents a one-point threshold at the largest distance.",
        ])
    else:
        lines.extend([
            "",
            "Diagnostic-only refinement: no candidate file was generated or replaced.",
            "The narrow-range step fit is provisional and must be merged with the full distance scan.",
        ])
    return "\n".join(lines)


def store_candidate_properties(result, stamp):
    ensure_directory(candidates_dir)
    props = Properties()
    props.setProperty("schema.version", "2")
    props.setProperty("source", "external-visual-distance-scan")
    props.setProperty("created", stamp)
    props.setProperty("speed-factor", "{0:.6f}".format(SPEED_FACTOR))
    props.setProperty("repeats", str(REPEATS))
    props.setProperty("stationary-vision-noise.mm", "{0:.9f}".format(
        result.getStationaryVisionNoiseMm()))
    props.setProperty("vision-detection-floor.mm", "{0:.9f}".format(
        result.getVisionDetectionFloorMm()))
    props.setProperty("fit.a.threshold.mm", "{0:.6f}".format(
        result.getAFit().getThresholdMm()))
    props.setProperty("fit.a.x.mm", "{0:.9f}".format(
        result.getAFit().getCorrectionXmm()))
    props.setProperty("fit.a.y.mm", "{0:.9f}".format(
        result.getAFit().getCorrectionYmm()))
    props.setProperty("fit.b.threshold.mm", "{0:.6f}".format(
        result.getBFit().getThresholdMm()))
    props.setProperty("fit.b.x.mm", "{0:.9f}".format(
        result.getBFit().getCorrectionXmm()))
    props.setProperty("fit.b.y.mm", "{0:.9f}".format(
        result.getBFit().getCorrectionYmm()))

    for direction, results in (("a", result.getAResults()),
                               ("b", result.getBResults())):
        props.setProperty("measurement.{0}.count".format(direction), str(len(results)))
        for index, item in enumerate(results):
            prefix = "measurement.{0}.{1}.".format(direction, index + 1)
            props.setProperty(prefix + "distance.mm", "{0:.6f}".format(
                item.getVirtualDistanceMm()))
            props.setProperty(prefix + "x.mm", "{0:.9f}".format(
                item.getCorrectionXmm()))
            props.setProperty(prefix + "y.mm", "{0:.9f}".format(
                item.getCorrectionYmm()))
            props.setProperty(prefix + "scatter.mm", "{0:.9f}".format(
                item.getScatterMm()))
            props.setProperty(prefix + "sample.count", str(len(item.getSamples())))
            for sample_index, sample in enumerate(item.getSamples()):
                sample_prefix = prefix + "sample.{0}.".format(sample_index + 1)
                props.setProperty(sample_prefix + "positive.x.mm", "{0:.9f}".format(
                    sample.getPositiveLocation().getX()))
                props.setProperty(sample_prefix + "positive.y.mm", "{0:.9f}".format(
                    sample.getPositiveLocation().getY()))
                props.setProperty(sample_prefix + "negative.x.mm", "{0:.9f}".format(
                    sample.getNegativeLocation().getX()))
                props.setProperty(sample_prefix + "negative.y.mm", "{0:.9f}".format(
                    sample.getNegativeLocation().getY()))

    props.setProperty("candidate.count", str(len(result.getCandidates())))
    for index, candidate in enumerate(result.getCandidates()):
        prefix = "candidate.{0}.".format(index + 1)
        props.setProperty(prefix + "scale", "{0:.6f}".format(candidate.getScale()))
        props.setProperty(prefix + "threshold.a.mm", "{0:.6f}".format(
            candidate.getThresholdAMm()))
        props.setProperty(prefix + "threshold.b.mm", "{0:.6f}".format(
            candidate.getThresholdBMm()))
        props.setProperty(prefix + "offset.a.x.mm", "{0:.9f}".format(
            candidate.getACorrectionXmm()))
        props.setProperty(prefix + "offset.a.y.mm", "{0:.9f}".format(
            candidate.getACorrectionYmm()))
        props.setProperty(prefix + "offset.b.x.mm", "{0:.9f}".format(
            candidate.getBCorrectionXmm()))
        props.setProperty(prefix + "offset.b.y.mm", "{0:.9f}".format(
            candidate.getBCorrectionYmm()))
        props.setProperty(prefix + "expected-worst.mm", "{0:.9f}".format(
            candidate.getExpectedWorstMm()))

    stamped = File(candidates_dir, "distance-scan-candidates-" + stamp + ".properties")
    latest = File(candidates_dir, "latest-distance-scan.properties")
    for output_file in (stamped, latest):
        stream = FileOutputStream(output_file)
        try:
            props.store(stream, "OpenPnP CoreXY external visual candidates")
        finally:
            stream.close()
    return stamped


def write_report(report, stamp):
    ensure_directory(reports_dir)
    report_file = File(reports_dir, REPORT_STEM + "-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report_file), "UTF-8")
    try:
        writer.write(report)
    finally:
        writer.close()
    return report_file


class ProgressWindow(object):
    def __init__(self):
        self.dialog = JDialog(None, "CoreXY Multi-distance Visual Scan", False)
        self.label = JLabel("Preparing multi-distance scan...")
        self.bar = JProgressBar(0, 100)
        self.bar.setStringPainted(True)
        panel = JPanel(BorderLayout(8, 8))
        panel.add(self.label, BorderLayout.NORTH)
        panel.add(self.bar, BorderLayout.CENTER)
        panel.setPreferredSize(Dimension(540, 76))
        self.dialog.getContentPane().add(panel)
        self.dialog.pack()
        self.dialog.setLocationRelativeTo(None)
        self.dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE)

    def show(self):
        self.dialog.setVisible(True)

    def close(self):
        self.dialog.dispose()

    def update(self, stage, completed, total):
        stage_text = str(stage)
        parts = stage_text.split("|")
        if parts[0] in ("distance-a", "distance-b") and len(parts) >= 4:
            label = "Distance {0}/{1}: {2} mm, direction {3}".format(
                parts[1], parts[2], parts[3], parts[0][-1].upper())
        else:
            labels = {
                "reference": "Centering on the Primary Fiducial",
                "vision-noise": "Measuring stationary vision noise",
                "return-reference": "Returning to the Primary Fiducial",
                "complete": "Measurement complete",
                "failed": "Measurement failed",
            }
            label = labels.get(parts[0], stage_text)
        percent = int(round(100.0 * completed / max(1, total)))

        class UpdateUi(Runnable):
            def run(inner_self):
                self.label.setText(label + "  {0}/{1}".format(completed, total))
                self.bar.setValue(percent)

        SwingUtilities.invokeLater(UpdateUi())


class ProgressListener(CoreXYVisualDiagnostics.ProgressListener):
    def __init__(self, window):
        self.window = window

    def onProgress(self, stage, completed, total):
        self.window.update(stage, completed, total)


status = str(CoreXYScriptBootstrap.status())
if "enabled=true" in status:
    raise RuntimeError("Disable CoreXY compensation before the distance scan.")

distance_text = ", ".join(["{0:g}".format(value) for value in DISTANCES_MM])
message = (
    "This uncompensated scan measures A and B at " + distance_text + " mm.\n\n"
    "The largest physical excursion is {0:g} mm on X/Y. Each distance and direction runs "
    "{1} return pairs at speed factor {2:.2f}. The scan may take several minutes.\n\n"
    "No candidate will be applied. X/Y stock backlash is temporarily disabled and restored. "
    "All endpoints retain a 1 mm soft-limit margin.\n\n"
    "Raise the head, clear the complete movement area, and keep emergency stop ready."
).format(max(DISTANCES_MM) * 0.5, REPEATS, SPEED_FACTOR)

answer = JOptionPane.showConfirmDialog(
    None, message, SCAN_TITLE,
    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)

if answer == JOptionPane.OK_OPTION:
    progress_window = ProgressWindow()
    progress_window.show()
    listener = ProgressListener(progress_window)

    def run_scan():
        try:
            result = CoreXYVisualDiagnostics.measureDistanceScan(
                DISTANCE_ARRAY, REPEATS, SPEED_FACTOR, SETTLE_MS,
                STATIONARY_CAPTURES, listener)
            stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
            report = build_report(result)
            report_file = write_report(report, stamp)
            if GENERATE_CANDIDATES:
                candidate_file = store_candidate_properties(result, stamp)
                output_text = (
                    "Generated four validation candidates: 25%, 50%, 75%, 100%.\n"
                    "No candidate has been applied.\n\nReport: " +
                    report_file.getAbsolutePath() + "\nCandidates: " +
                    candidate_file.getAbsolutePath())
            else:
                output_text = (
                    "Diagnostic-only refinement. No candidate file was generated or replaced.\n"
                    "No candidate has been applied.\n\nReport: " +
                    report_file.getAbsolutePath())
            summary = (fit_report(result.getAFit()) + "\n" +
                       fit_report(result.getBFit()) + "\n\n" + output_text)

            class ShowResult(Runnable):
                def run(inner_self):
                    progress_window.close()
                    JOptionPane.showMessageDialog(
                        None, summary, SCAN_TITLE + " Result",
                        JOptionPane.INFORMATION_MESSAGE)

            SwingUtilities.invokeLater(ShowResult())
        except Exception as error:
            text = str(error)

            class ShowError(Runnable):
                def run(inner_self):
                    progress_window.close()
                    JOptionPane.showMessageDialog(
                        None, text, "CoreXY Distance Scan Error",
                        JOptionPane.ERROR_MESSAGE)

            SwingUtilities.invokeLater(ShowError())

    UiUtils.submitUiMachineTask(run_scan)
