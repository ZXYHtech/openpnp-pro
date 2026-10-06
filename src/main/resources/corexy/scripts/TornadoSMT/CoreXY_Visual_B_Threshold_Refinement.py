from java.io import File
from org.openpnp.model import Configuration


COREXY_DISTANCE_SEQUENCE = [30.0, 35.0, 40.0, 45.0, 50.0, 55.0, 60.0]
COREXY_EXPLICIT_SPEED = 0.20
COREXY_GENERATE_CANDIDATES = False
COREXY_SCAN_TITLE = "CoreXY B Threshold Refinement"
COREXY_REPORT_STEM = "visual-b-threshold-refinement"

runtime_file = File(
    Configuration.get().getScripting().getScriptsDirectory(),
    "TornadoSMT/CoreXY_Visual_Distance_Scan.py")

execfile(runtime_file.getCanonicalPath(), globals())
