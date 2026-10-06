from java.lang import String
from java.nio.file import Files
from org.openpnp.model import Configuration


source = Configuration.get().getScripting().getScriptsDirectory()
source = source.toPath().resolve(
    "TornadoSMT/CoreXY_Agent_Surface_Physical_Smoke.pytxt").toFile()
code = unicode(String(Files.readAllBytes(source.toPath()), "UTF-8"))
scope = dict(globals())
scope["COREXY_LEGACY_FIXED_SMOKE"] = True
exec compile(code, source.getAbsolutePath(), "exec") in scope
