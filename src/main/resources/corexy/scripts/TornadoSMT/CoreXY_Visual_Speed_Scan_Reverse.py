from java.io import File
from org.openpnp.model import Configuration


COREXY_SPEED_SEQUENCE = [1.00, 0.75, 0.50, 0.20]
runtime_file = File(
    Configuration.get().getScripting().getScriptsDirectory(),
    "TornadoSMT/CoreXY_Visual_Speed_Scan.py")

execfile(runtime_file.getCanonicalPath(), globals())
