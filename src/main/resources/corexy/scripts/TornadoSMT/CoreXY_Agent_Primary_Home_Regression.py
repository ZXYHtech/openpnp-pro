from java.lang import String
from java.nio.file import Files
from org.openpnp.model import Configuration


source = Configuration.get().getScripting().getScriptsDirectory()
source = source.toPath().resolve(
    "TornadoSMT/CoreXY_Agent_Home_B_Retest.pytxt").toFile()
code = unicode(String(Files.readAllBytes(source.toPath()), "UTF-8"))
scope = dict(globals())
scope["COREXY_PRIMARY_AUTO_HOME_REGRESSION"] = True
exec compile(code, source.getAbsolutePath(), "exec") in scope
