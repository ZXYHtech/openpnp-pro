from java.lang import String
from java.nio.file import Files
from org.openpnp.model import Configuration


source = Configuration.get().getScripting().getScriptsDirectory()
source = source.toPath().resolve(
    "TornadoSMT/CoreXY_Agent_Surface_Physical_Validate.pytxt").toFile()
code = unicode(String(Files.readAllBytes(source.toPath()), "UTF-8"))
scope = dict(globals())
scope["COREXY_TRADITIONAL_SEGMENTED_VALIDATION"] = True
exec compile(code, source.getAbsolutePath(), "exec") in scope
