from java.io import File
from org.openpnp.model import Configuration


COREXY_DISTANCE_SEQUENCE = [30.0, 40.0, 50.0, 60.0, 90.0, 120.0]
COREXY_EXPLICIT_SPEED = 0.75
COREXY_GENERATE_CANDIDATES = False
COREXY_SCAN_TITLE = "CoreXY 75% Speed Distance Profile"
COREXY_REPORT_STEM = "visual-distance-profile-75"

runtime_file = File(
    Configuration.get().getScripting().getScriptsDirectory(),
    "TornadoSMT/CoreXY_Visual_Distance_Scan.py")

execfile(runtime_file.getCanonicalPath(), globals())
