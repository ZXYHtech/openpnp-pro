from __future__ import unicode_literals

import math
from java.io import (File, FileInputStream, FileOutputStream,
                     OutputStreamWriter)
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import JOptionPane
from org.openpnp.model import Configuration


LEGACY_FIXED_REVIEW = bool(globals().get(
    "COREXY_LEGACY_FIXED_REVIEW", False))
TRADITIONAL_SEGMENTED_REVIEW = bool(globals().get(
    "COREXY_TRADITIONAL_SEGMENTED_REVIEW", False))
scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
diagnostics_dir = File(runtime_dir, "diagnostics")
reports_dir = File(runtime_dir, "reports")
physical_file = File(
    diagnostics_dir,
    ("latest-legacy-fixed-physical-validation.properties"
     if LEGACY_FIXED_REVIEW else
     "latest-traditional-segmented-physical-validation.properties"
     if TRADITIONAL_SEGMENTED_REVIEW else
     "latest-auto-calibration-physical-validation.properties"))


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


def set_float(props, key, value):
    props.setProperty(key, "{0:.9f}".format(float(value)))


def load_metrics(props, prefix):
    return {
        "rms": float(required(props, prefix + ".rms.mm")),
        "p95": float(required(props, prefix + ".p95.mm")),
        "maximum": float(required(props, prefix + ".maximum.mm"))
    }


def store_metrics(props, prefix, metrics):
    set_float(props, prefix + ".rms.mm", metrics["rms"])
    set_float(props, prefix + ".p95.mm", metrics["p95"])
    set_float(props, prefix + ".maximum.mm", metrics["maximum"])


def improvement(before, after):
    if before <= 0.0:
        return 0.0
    return 100.0 * (before - after) / before


def metrics_line(label, baseline, physical):
    return (
        "{0}: RMS {1:.6f} -> {2:.6f} mm ({3:+.1f}% improvement); "
        "P95 {4:.6f} -> {5:.6f}; maximum {6:.6f} -> {7:.6f} mm"
    ).format(
        label, baseline["rms"], physical["rms"],
        improvement(baseline["rms"], physical["rms"]),
        baseline["p95"], physical["p95"],
        baseline["maximum"], physical["maximum"])


def write_properties(props, stamped, latest):
    for output in [stamped, latest]:
        stream = FileOutputStream(output)
        try:
            props.store(stream, "OpenPnP CoreXY physical validation review")
        finally:
            stream.close()


def review():
    source_physical = physical_file
    if not source_physical.isFile():
        prefix = ("legacy-fixed-physical-validation-"
                  if LEGACY_FIXED_REVIEW else
                  "traditional-segmented-physical-validation-"
                  if TRADITIONAL_SEGMENTED_REVIEW else
                  "auto-calibration-physical-validation-")
        matches = [path for path in diagnostics_dir.listFiles()
                   if path.isFile() and path.getName().startswith(prefix) and
                   path.getName().endswith(".properties")]
        if matches:
            source_physical = max(matches, key=lambda path: path.lastModified())
    if not source_physical.isFile():
        raise IOError("No physical validation result was found.")
    physical = load_properties(source_physical)
    if required(physical, "compensation.applied") != "true":
        raise ValueError("The source result was not measured with compensation.")
    source_state = required(physical, "state")
    if source_state not in ["passed", "failed-acceptance"]:
        raise ValueError("The latest physical validation did not complete.")

    baseline_all = load_metrics(physical, "baseline.all")
    baseline_a = load_metrics(physical, "baseline.a")
    baseline_b = load_metrics(physical, "baseline.b")
    measured_all = load_metrics(physical, "physical.all")
    measured_a = load_metrics(physical, "physical.a")
    measured_b = load_metrics(physical, "physical.b")

    point_changes = []
    floors = []
    stage_count = int(required(physical, "stage.count"))
    for stage_index in range(1, stage_count + 1):
        stage = "stage.{0}".format(stage_index)
        speed = float(required(physical, stage + ".speed-factor"))
        floors.append(float(required(
            physical, stage + ".vision-detection-floor.mm")))
        for direction in ["a", "b"]:
            count = int(required(
                physical, stage + "." + direction + ".distance.count"))
            for distance_index in range(1, count + 1):
                prefix = stage + "." + direction + ".distance.{0}".format(
                    distance_index)
                point_changes.append({
                    "direction": direction.upper(),
                    "speed": speed,
                    "distance": float(required(
                        physical, prefix + ".virtual-distance.mm")),
                    "baseline": None,
                    "measured": float(required(
                        physical, prefix + ".magnitude.mm")),
                    "prefix": prefix
                })

    baseline_file = File(required(physical, "source-baseline"))
    if not baseline_file.isFile():
        raise IOError("Source baseline was not found: " +
                      baseline_file.getAbsolutePath())
    baseline = load_properties(baseline_file)
    for point in point_changes:
        point["baseline"] = float(required(
            baseline, point["prefix"] + ".magnitude.mm"))
        point["worsening"] = point["measured"] - point["baseline"]
    worst = max(point_changes, key=lambda value: value["worsening"])
    local_limit = max(0.015, max(floors) * 3.0)
    target_tolerance = float(required(
        physical, "target.radial-tolerance.mm"))
    target_maximum_guard = float(required(
        physical, "target.maximum-residual-guard.mm"))

    checks = [
        ("All RMS improves by at least 10%",
         measured_all["rms"] <= baseline_all["rms"] * 0.90),
        ("B RMS improves by at least 10%",
         measured_b["rms"] <= baseline_b["rms"] * 0.90),
        ("All P95 does not worsen",
         measured_all["p95"] <= baseline_all["p95"]),
        ("All maximum does not worsen",
         measured_all["maximum"] <= baseline_all["maximum"]),
        ("A remains within its noise guardrail",
         measured_a["rms"] <= max(
             baseline_a["rms"] * 1.35, baseline_a["rms"] + 0.010)),
        ("No local point exceeds the vision-aware worsening guardrail",
         worst["worsening"] <= local_limit),
        ("All P95 meets the user 2D radial target",
         measured_all["p95"] <= target_tolerance),
        ("All maximum remains inside the target vision guard",
         measured_all["maximum"] <= target_maximum_guard)
    ]
    passed = all([value for label, value in checks])

    ensure_directory(diagnostics_dir)
    ensure_directory(reports_dir)
    stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    review_prefix = ("legacy-fixed-physical-review"
                     if LEGACY_FIXED_REVIEW else
                     "traditional-segmented-physical-review"
                     if TRADITIONAL_SEGMENTED_REVIEW else
                     "auto-calibration-physical-review")
    stamped = File(
        diagnostics_dir, review_prefix + "-" + stamp + ".properties")
    latest = File(diagnostics_dir, "latest-" + review_prefix + ".properties")
    review_props = Properties()
    review_props.setProperty("schema.version", "1")
    review_props.setProperty("source", "physical-validation-review")
    review_props.setProperty("state", "passed" if passed else "failed")
    review_props.setProperty("created", stamp)
    review_props.setProperty(
        "acceptance.policy", ("legacy-fixed-vision-aware-v1"
                              if LEGACY_FIXED_REVIEW else
                              "traditional-segmented-vision-aware-v1"
                              if TRADITIONAL_SEGMENTED_REVIEW else
                              "vision-aware-v2"))
    review_props.setProperty(
        "source-physical-validation", source_physical.getCanonicalPath())
    review_props.setProperty(
        "source-physical-validation-state", source_state)
    review_props.setProperty(
        "source-baseline", baseline_file.getCanonicalPath())
    review_props.setProperty(
        "validation.passed", str(passed).lower())
    review_props.setProperty(
        "compensation.applied-during-source-measurement", "true")
    review_props.setProperty(
        "compensation.changed-during-review", "false")
    set_float(review_props, "model.gain",
              float(required(physical, "model.gain")))
    set_float(review_props, "target.radial-tolerance.mm", target_tolerance)
    set_float(review_props, "target.maximum-residual-guard.mm",
              target_maximum_guard)
    store_metrics(review_props, "baseline.all", baseline_all)
    store_metrics(review_props, "baseline.a", baseline_a)
    store_metrics(review_props, "baseline.b", baseline_b)
    store_metrics(review_props, "physical.all", measured_all)
    store_metrics(review_props, "physical.a", measured_a)
    store_metrics(review_props, "physical.b", measured_b)
    set_float(review_props, "physical.maximum-worsening.mm",
              worst["worsening"])
    set_float(review_props, "acceptance.local-worsening-limit.mm",
              local_limit)
    review_props.setProperty(
        "physical.maximum-worsening.direction", worst["direction"])
    set_float(review_props, "physical.maximum-worsening.speed-factor",
              worst["speed"])
    set_float(review_props, "physical.maximum-worsening.distance.mm",
              worst["distance"])
    set_float(review_props, "physical.maximum-worsening.baseline.mm",
              worst["baseline"])
    set_float(review_props, "physical.maximum-worsening.result.mm",
              worst["measured"])
    for index, check in enumerate(checks):
        review_props.setProperty(
            "acceptance.check.{0}.description".format(index + 1), check[0])
        review_props.setProperty(
            "acceptance.check.{0}.passed".format(index + 1),
            str(check[1]).lower())
    write_properties(review_props, stamped, latest)

    lines = [
        ("OpenPnP CoreXY Legacy Fixed-vector Validation Review"
         if LEGACY_FIXED_REVIEW else
         "OpenPnP CoreXY Traditional Segmented Validation Review"
         if TRADITIONAL_SEGMENTED_REVIEW else
         "OpenPnP CoreXY Physical Validation Review"),
        "",
        "Review state: " + ("passed" if passed else "failed"),
        "Policy: " + ("legacy-fixed-vision-aware-v1"
                      if LEGACY_FIXED_REVIEW else
                      "traditional-segmented-vision-aware-v1"
                      if TRADITIONAL_SEGMENTED_REVIEW else "vision-aware-v2"),
        "No movement occurred and no configuration was changed.",
        "Source measurement state: " + source_state,
        "Model gain during source measurement: {0:.0%}".format(
            float(required(physical, "model.gain"))),
        "User 2D radial target: {0:.6f} mm; maximum guard: "
        "{1:.6f} mm".format(target_tolerance, target_maximum_guard),
        "",
        metrics_line("All", baseline_all, measured_all),
        metrics_line("A", baseline_a, measured_a),
        metrics_line("B", baseline_b, measured_b),
        "Worst cell: {0}, speed {1:.0%}, distance {2:.1f} mm; "
        "{3:.6f} -> {4:.6f} mm".format(
            worst["direction"], worst["speed"], worst["distance"],
            worst["baseline"], worst["measured"]),
        "Worst same-cell worsening: {0:.6f} mm; "
        "vision-aware limit {1:.6f} mm".format(
            worst["worsening"], local_limit),
        "",
        "Acceptance checks:"
    ]
    for label, value in checks:
        lines.append(("PASS  " if value else "FAIL  ") + label)
    lines.extend([
        "",
        "Physical validation review passed: " + str(passed).lower(),
        "The original physical measurement file remains unchanged."
    ])
    if LEGACY_FIXED_REVIEW:
        lines.extend([
            "", "中文摘要：",
            "系统复核结果：" + ("通过" if passed else "未达到全部系统目标"),
            "总RMS: {0:.6f} -> {1:.6f} mm；P95: {2:.6f} -> {3:.6f} mm；最大值: {4:.6f} -> {5:.6f} mm".format(
                baseline_all["rms"], measured_all["rms"],
                baseline_all["p95"], measured_all["p95"],
                baseline_all["maximum"], measured_all["maximum"]),
            "系统判定与用户有条件接受将分别记录；原始测量文件保持不变。"
        ])

    report = File(reports_dir, review_prefix + "-" + stamp + ".txt")
    writer = OutputStreamWriter(FileOutputStream(report), "UTF-8")
    try:
        writer.write("\r\n".join(lines))
    finally:
        writer.close()
    message = "\n".join(lines) + "\n\nReview: " + \
        stamped.getAbsolutePath() + "\nReport: " + report.getAbsolutePath()
    JOptionPane.showMessageDialog(
        None, message,
        ("CoreXY Legacy Fixed-vector Validation Review"
         if LEGACY_FIXED_REVIEW else "CoreXY Physical Validation Review"),
        JOptionPane.INFORMATION_MESSAGE if passed
        else JOptionPane.WARNING_MESSAGE)


try:
    review()
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error),
        ("CoreXY Legacy Fixed-vector Review Error"
         if LEGACY_FIXED_REVIEW else
         "CoreXY Physical Validation Review Error"),
        JOptionPane.ERROR_MESSAGE)
    raise
