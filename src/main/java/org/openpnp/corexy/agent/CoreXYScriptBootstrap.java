package org.openpnp.corexy.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.jar.JarFile;

import net.bytebuddy.agent.ByteBuddyAgent;

/** Entry point designed to be imported from an OpenPnP Jython script. */
public final class CoreXYScriptBootstrap {
    private static volatile JarFile appendedJar;

    private CoreXYScriptBootstrap() {
    }

    public static synchronized String install(String jarPath, String configPath) throws Exception {
        if (CoreXYAgent.hasNativeHooks()) {
            return CoreXYAgent.installNative(configPath);
        }
        File jar = new File(jarPath).getCanonicalFile();
        File config = new File(configPath).getCanonicalFile();
        if (!jar.isFile()) {
            throw new IllegalArgumentException("CoreXY Agent JAR not found: " + jar);
        }
        if (!config.isFile()) {
            throw new IllegalArgumentException("CoreXY configuration not found: " + config);
        }

        Instrumentation instrumentation = CoreXYAgent.getStartupInstrumentation();
        if (instrumentation == null) {
            instrumentation = ByteBuddyAgent.install();
        }
        if (appendedJar == null) {
            appendedJar = new JarFile(jar);
            instrumentation.appendToSystemClassLoaderSearch(appendedJar);
        }

        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        Class<?> systemAgent = Class.forName(
                "org.openpnp.corexy.agent.CoreXYAgent", true, systemLoader);
        Method install = systemAgent.getMethod(
                "install", Instrumentation.class, String.class);
        try {
            return String.valueOf(install.invoke(null, instrumentation, config.getPath()));
        }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    public static String status() throws Exception {
        Class<?> systemAgent = Class.forName(
                "org.openpnp.corexy.agent.CoreXYAgent", true,
                ClassLoader.getSystemClassLoader());
        return String.valueOf(systemAgent.getMethod("getStatus").invoke(null));
    }

    public static String reload() throws Exception {
        Class<?> systemAgent = Class.forName(
                "org.openpnp.corexy.agent.CoreXYAgent", true,
                ClassLoader.getSystemClassLoader());
        return String.valueOf(systemAgent.getMethod("reload").invoke(null));
    }

    public static String loadTransient(String configPath) throws Exception {
        Class<?> systemAgent = Class.forName(
                "org.openpnp.corexy.agent.CoreXYAgent", true,
                ClassLoader.getSystemClassLoader());
        try {
            return String.valueOf(systemAgent.getMethod(
                    "loadTransient", String.class).invoke(null, configPath));
        }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    public static String loadImmediate(String configPath) throws Exception {
        Class<?> systemAgent = Class.forName(
                "org.openpnp.corexy.agent.CoreXYAgent", true,
                ClassLoader.getSystemClassLoader());
        try {
            return String.valueOf(systemAgent.getMethod(
                    "loadImmediate", String.class).invoke(null, configPath));
        }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }
}
