from java.awt import GridBagConstraints, GridBagLayout, Insets
from java.io import File, FileInputStream, FileOutputStream
from java.text import SimpleDateFormat
from java.util import Date, Properties
from javax.swing import (JCheckBox, JLabel, JOptionPane, JPanel, JTextField)
from org.openpnp.machine.reference import ReferenceHead
from org.openpnp.model import Configuration, LengthUnit


DEFAULT_TARGET_MM = 0.050
DEFAULT_FLOOR_MULTIPLE = 3.0
WARNING_ABOVE_MM = 0.080
ADVANCED_ABOVE_MM = 0.100
ABSOLUTE_MAXIMUM_MM = 0.200

scripts_dir = Configuration.get().getScripting().getScriptsDirectory()
runtime_dir = File(scripts_dir, "TornadoSMT/OpenPnP-CoreXY")
policy_file = File(runtime_dir, "config/calibration-policy.properties")
diagnostics_dir = File(runtime_dir, "diagnostics")


def load_properties(path):
    props = Properties()
    if path.isFile():
        stream = FileInputStream(path)
        try:
            props.load(stream)
        finally:
            stream.close()
    return props


def property_float(props, key, fallback):
    value = props.getProperty(key)
    return float(value) if value is not None else float(fallback)


def set_float(props, key, value):
    props.setProperty(key, "{0:.9f}".format(float(value)))


def latest_vision_floor():
    candidates = [
        ("latest-auto-calibration-analysis.properties", "observed-floor.mm"),
        ("latest-auto-calibration-validation.properties", "observed-floor.mm"),
        ("latest-production-health.properties", "health.vision-floor.mm"),
        ("latest-production-health-confirmation.properties",
         "confirmation.vision-floor.mm")
    ]
    values = []
    for name, key in candidates:
        path = File(diagnostics_dir, name)
        if not path.isFile():
            continue
        props = load_properties(path)
        value = props.getProperty(key)
        if value is not None:
            try:
                number = float(value)
                if number > 0.0:
                    values.append(number)
            except Exception:
                pass
    return max(values) if values else 0.005


def constraints(x, y, width=1, weightx=0.0, fill=GridBagConstraints.NONE):
    item = GridBagConstraints()
    item.gridx = x
    item.gridy = y
    item.gridwidth = width
    item.weightx = weightx
    item.fill = fill
    item.anchor = GridBagConstraints.WEST
    item.insets = Insets(3, 5, 3, 5)
    return item


def parse_decimal(field, label):
    try:
        value = float(str(field.getText()).strip())
    except Exception:
        raise ValueError(label + " must be a decimal number.")
    if value <= 0.0:
        raise ValueError(label + " must be greater than zero.")
    return value


def parse_list(field, label):
    values = []
    try:
        for token in str(field.getText()).replace(";", ",").split(","):
            if token.strip():
                values.append(float(token.strip()))
    except Exception:
        raise ValueError(label + " must be a comma-separated number list.")
    if not values or any([value <= 0.0 for value in values]):
        raise ValueError(label + " values must be greater than zero.")
    return sorted(set(values))


try:
    machine = Configuration.get().getMachine()
    head = machine.getDefaultHead()
    if not isinstance(head, ReferenceHead):
        raise ValueError("The default head is not a ReferenceHead.")

    primary = head.getCalibrationPrimaryFiducialLocation().convertToUnits(
        LengthUnit.Millimeters)
    home = head.getHomingFiducialLocation().convertToUnits(
        LengthUnit.Millimeters)
    if abs(home.getX()) < 0.000001 or abs(home.getY()) < 0.000001:
        raise ValueError(
            "Configure the visual Home fiducial before enabling Home retest.")

    props = load_properties(policy_file)
    floor = latest_vision_floor()
    floor_multiple = property_float(
        props, "target.minimum-vision-floor-multiple", DEFAULT_FLOOR_MULTIPLE)
    minimum_target = floor * floor_multiple

    target_field = JTextField("{0:.3f}".format(property_float(
        props, "target.radial-tolerance.mm", DEFAULT_TARGET_MM)), 10)
    distance_field = JTextField("{0:.1f}".format(property_float(
        props, "home-reference.preferred-distance.mm", 270.0)), 10)
    speed_field = JTextField("{0:.0f}".format(100.0 * property_float(
        props, "home-reference.speed-factor", 0.90)), 10)
    enabled_field = JCheckBox(
        "Enable Home validation reference",
        str(props.getProperty("home-reference.enabled", "true")).lower() ==
        "true")
    scan_speeds = props.getProperty(
        "home-surface.speeds", "0.40,0.70,0.90")
    scan_speed_field = JTextField(
        ",".join(["{0:g}".format(float(value) * 100.0)
                  for value in scan_speeds.split(",")]), 20)
    scan_distance_field = JTextField(props.getProperty(
        "home-surface.distances.mm", "15,30,60,120,180,270"), 20)
    scan_repeats_field = JTextField(props.getProperty(
        "home-surface.repeats", "5"), 10)

    panel = JPanel(GridBagLayout())
    row = 0
    panel.add(JLabel("2D radial residual target (mm):"), constraints(0, row))
    panel.add(target_field, constraints(1, row))
    row += 1
    panel.add(JLabel(
        "Default 0.050 mm; current measurable minimum is {0:.6f} mm "
        "({1:.1f} x vision floor {2:.6f} mm).".format(
            minimum_target, floor_multiple, floor)),
        constraints(0, row, 2, 1.0, GridBagConstraints.HORIZONTAL))
    row += 1
    panel.add(JLabel("Home preferred inward distance (mm):"),
              constraints(0, row))
    panel.add(distance_field, constraints(1, row))
    row += 1
    panel.add(JLabel("Home retest speed (%):"), constraints(0, row))
    panel.add(speed_field, constraints(1, row))
    row += 1
    panel.add(JLabel("Home surface speeds (%):"), constraints(0, row))
    panel.add(scan_speed_field, constraints(1, row))
    row += 1
    panel.add(JLabel("Home surface inward distances (mm):"), constraints(0, row))
    panel.add(scan_distance_field, constraints(1, row))
    row += 1
    panel.add(JLabel("Home surface repeats per cell:"), constraints(0, row))
    panel.add(scan_repeats_field, constraints(1, row))
    row += 1
    panel.add(enabled_field, constraints(0, row, 2))
    row += 1
    panel.add(JLabel(
        "Primary Fiducial: X={0:.3f}, Y={1:.3f}, Z={2:.3f} mm".format(
            primary.getX(), primary.getY(), primary.getZ())),
        constraints(0, row, 2))
    row += 1
    panel.add(JLabel(
        "Home visual fiducial: X={0:.3f}, Y={1:.3f} mm; validation only".format(
            home.getX(), home.getY())), constraints(0, row, 2))
    row += 1
    panel.add(JLabel(
        "The Home corner can isolate B+/B- with inward X/Y returns; "
        "it cannot independently identify A+/A-."), constraints(0, row, 2))

    answer = JOptionPane.showConfirmDialog(
        None, panel, "CoreXY Compensation Target and References",
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
    if answer != JOptionPane.OK_OPTION:
        raise RuntimeError("CoreXY compensation policy update cancelled.")

    target = parse_decimal(target_field, "Radial residual target")
    preferred_distance = parse_decimal(distance_field, "Home retest distance")
    speed_percent = parse_decimal(speed_field, "Home retest speed")
    scan_speed_percent = parse_list(scan_speed_field, "Home surface speeds")
    scan_distances = parse_list(
        scan_distance_field, "Home surface distances")
    scan_repeats = int(parse_decimal(
        scan_repeats_field, "Home surface repeats"))
    if target < minimum_target:
        raise ValueError(
            "The target is below the measurable minimum: {0:.6f} mm.".format(
                minimum_target))
    if target > ABSOLUTE_MAXIMUM_MM:
        raise ValueError(
            "The target exceeds the safety maximum {0:.3f} mm.".format(
                ABSOLUTE_MAXIMUM_MM))
    if speed_percent > 100.0:
        raise ValueError("Home retest speed must not exceed 100%.")
    if len(scan_speed_percent) < 2 or max(scan_speed_percent) > 100.0:
        raise ValueError(
            "Home surface scan requires at least two speeds not above 100%.")
    if len(scan_distances) < 3 or min(scan_distances) < 15.0:
        raise ValueError(
            "Home surface scan requires at least three distances >= 15 mm.")
    if scan_repeats < 3 or scan_repeats > 20:
        raise ValueError("Home surface repeats must be between 3 and 20.")

    if target > WARNING_ABOVE_MM:
        warning = (
            "A {0:.3f} mm residual target can affect small packages and "
            "fine-pitch placement.\n\nContinue?"
        ).format(target)
        if JOptionPane.showConfirmDialog(
                None, warning, "CoreXY Target Warning",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION:
            raise RuntimeError("CoreXY compensation policy update cancelled.")
    if target > ADVANCED_ABOVE_MM:
        token = JOptionPane.showInputDialog(
            None,
            "This target is above 0.100 mm. Type HIGH TOLERANCE to confirm.",
            "CoreXY Advanced Confirmation",
            JOptionPane.WARNING_MESSAGE)
        if token is None or str(token).strip() != "HIGH TOLERANCE":
            raise RuntimeError("Advanced tolerance confirmation was not accepted.")

    if not policy_file.getParentFile().isDirectory() and \
            not policy_file.getParentFile().mkdirs():
        raise IOError("Cannot create policy directory.")
    props.setProperty("schema.version", "1")
    props.setProperty("updated",
                      SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()))
    props.setProperty("policy.kind", "minimum-necessary-compensation")
    set_float(props, "target.radial-tolerance.mm", target)
    set_float(props, "target.minimum-vision-floor-multiple", floor_multiple)
    set_float(props, "target.warning-above.mm", WARNING_ABOVE_MM)
    set_float(props, "target.advanced-confirmation-above.mm", ADVANCED_ABOVE_MM)
    set_float(props, "target.absolute-maximum.mm", ABSOLUTE_MAXIMUM_MM)
    set_float(props, "target.observed-vision-floor.mm", floor)
    set_float(props, "target.effective-minimum.mm", minimum_target)
    props.setProperty("home-reference.enabled",
                      str(enabled_field.isSelected()).lower())
    props.setProperty("home-reference.role", "validation-only")
    props.setProperty("home-reference.observable-state", "B")
    props.setProperty("home-reference.unobservable-state", "A")
    set_float(props, "home-reference.x.mm", home.getX())
    set_float(props, "home-reference.y.mm", home.getY())
    set_float(props, "home-reference.z.mm", primary.getZ())
    set_float(props, "home-reference.feature-diameter.mm",
              head.getCalibrationPrimaryFiducialDiameter().convertToUnits(
                  LengthUnit.Millimeters).getValue())
    set_float(props, "home-reference.preferred-distance.mm", preferred_distance)
    set_float(props, "home-reference.minimum-distance.mm", 15.0)
    set_float(props, "home-reference.speed-factor", speed_percent / 100.0)
    props.setProperty("home-reference.repeats", "7")
    props.setProperty("home-reference.settle.ms", "500")
    props.setProperty("home-reference.stationary-captures", "9")
    props.setProperty("home-surface.speeds", ",".join(
        ["{0:.6f}".format(value / 100.0) for value in scan_speed_percent]))
    props.setProperty("home-surface.distances.mm", ",".join(
        ["{0:.3f}".format(value) for value in scan_distances]))
    props.setProperty("home-surface.repeats", str(scan_repeats))

    stream = FileOutputStream(policy_file)
    try:
        props.store(stream, "OpenPnP CoreXY calibration policy")
    finally:
        stream.close()

    JOptionPane.showMessageDialog(
        None,
        "Policy saved.\n\n"
        "2D radial target: {0:.3f} mm\n"
        "Vision-derived minimum: {1:.6f} mm\n"
        "Home reference: ({2:.3f}, {3:.3f}) mm\n"
        "Role: validation only; B state observable; A state unavailable\n\n"
        "No motion occurred and production compensation was not changed.\n\n"
        "File: {4}".format(
            target, minimum_target, home.getX(), home.getY(),
            policy_file.getAbsolutePath()),
        "CoreXY Compensation Policy", JOptionPane.INFORMATION_MESSAGE)
except RuntimeError as error:
    if "cancel" not in str(error).lower() and \
            "accepted" not in str(error).lower():
        JOptionPane.showMessageDialog(
            None, str(error), "CoreXY Compensation Policy",
            JOptionPane.ERROR_MESSAGE)
except Exception as error:
    JOptionPane.showMessageDialog(
        None, str(error), "CoreXY Compensation Policy Error",
        JOptionPane.ERROR_MESSAGE)
    raise
