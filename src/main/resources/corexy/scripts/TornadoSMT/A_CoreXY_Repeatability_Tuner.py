from java.io import File
from java.lang import Class, String
from java.nio.file import Files


RUNTIME_NAME = "TornadoSMT/CoreXY_Repeatability_Tuner.pytxt"
MINIMUM_MARK_LIMIT = 1000000


runtime_file = File(scripting.getScriptsDirectory(), RUNTIME_NAME)
if not runtime_file.isFile():
    raise IOError("CoreXY Tuner runtime file is missing: " + runtime_file.getAbsolutePath())

source = unicode(String(Files.readAllBytes(runtime_file.toPath()), "UTF-8"))

# Jython 2.7.1 hard-codes a 100000-character parser mark. The full UI is
# intentionally loaded after raising that limit so future help text can grow.
parser_class = Class.forName("org.python.core.ParserFacade")
mark_limit = parser_class.getDeclaredField("MARK_LIMIT")
mark_limit.setAccessible(True)
required_limit = max(MINIMUM_MARK_LIMIT, len(source) + 65536)
mark_limit.setInt(None, required_limit)

# JSR-223 may discard names imported into its scope after this launcher exits.
# Keep one ordinary dictionary as the globals mapping for every Tuner callback.
runtime_code = compile(source, runtime_file.getAbsolutePath(), "exec")
corexy_tuner_namespace = dict(globals())
exec runtime_code in corexy_tuner_namespace
COREXY_TUNER_NAMESPACE = corexy_tuner_namespace
