using System;
using System.Diagnostics;
using System.IO;
using System.Text;

internal static class LightWrapper
{
    private static string Quote(string value)
    {
        var result = new StringBuilder("\"");
        var backslashes = 0;
        foreach (var character in value) {
            if (character == '\\') {
                backslashes++;
                continue;
            }
            if (character == '"') {
                result.Append('\\', backslashes * 2 + 1);
                result.Append('"');
                backslashes = 0;
                continue;
            }
            result.Append('\\', backslashes);
            backslashes = 0;
            result.Append(character);
        }
        result.Append('\\', backslashes * 2);
        result.Append('"');
        return result.ToString();
    }

    private static int Main(string[] args)
    {
        var wrapperDirectory = AppDomain.CurrentDomain.BaseDirectory;
        var realLight = Path.GetFullPath(
                Path.Combine(wrapperDirectory, "..", "wix314", "light.exe"));
        var arguments = new StringBuilder();
        foreach (var argument in args) {
            if (arguments.Length > 0) {
                arguments.Append(' ');
            }
            arguments.Append(Quote(argument));
        }
        if (arguments.Length > 0) {
            arguments.Append(' ');
        }
        arguments.Append("-sval");

        var startInfo = new ProcessStartInfo(realLight, arguments.ToString()) {
            UseShellExecute = false
        };
        using (var process = Process.Start(startInfo)) {
            process.WaitForExit();
            return process.ExitCode;
        }
    }
}
