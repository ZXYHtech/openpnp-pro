package org.openpnp.corexy.agent;

import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.openpnp.Main;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.model.Configuration;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/** Installed into the application class loader by {@link CoreXYScriptBootstrap}. */
public final class CoreXYAgent {
    private static volatile boolean installed;
    private static volatile Path configPath;
    private static volatile Instrumentation startupInstrumentation;

    /** Launcher supplied instrumentation avoids external JDK/self-attach requirements. */
    public static void premain(String arguments, Instrumentation instrumentation) {
        startupInstrumentation = instrumentation;
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        startupInstrumentation = instrumentation;
    }

    public static void rememberConfiguration(String configuredPath) {
        configPath = Paths.get(configuredPath).toAbsolutePath().normalize();
    }

    public static Instrumentation getStartupInstrumentation() {
        return startupInstrumentation;
    }

    private CoreXYAgent() {
    }

    public static synchronized String install(Instrumentation instrumentation,
            String configuredPath) throws Exception {
        Path requestedConfig = Paths.get(configuredPath).toAbsolutePath().normalize();
        String version = Main.getBuildString();

        CoreXYCompatibilityCheck.verify();
        if (hasNativeHooks()) {
            installed = true;
            configPath = requestedConfig;
            CoreXYRuntime.configure(configPath, version);
            return getStatus();
        }
        if (!installed) {
            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .type(named("org.openpnp.machine.reference.driver.AbstractMotionPlanner"))
                    .transform((builder, type, classLoader, module, protectionDomain) -> builder
                            .visit(Advice.to(CoreXYAdvice.Plan.class).on(
                                    named("createBacklashCompensatedMotion")
                                            .and(isDeclaredBy(AbstractMotionPlanner.class))
                                            .and(takesArguments(5))))
                            .visit(Advice.to(CoreXYAdvice.SetGlobalOffsets.class).on(
                                    named("setGlobalOffsets").and(takesArguments(1))))
                            .visit(Advice.to(CoreXYAdvice.WaitForCompletion.class).on(
                                    named("waitForCompletion").and(takesArguments(2))))
                            .visit(Advice.to(CoreXYAdvice.Home.class).on(
                                    named("home").and(takesArguments(0))))
                            .visit(Advice.to(CoreXYAdvice.Unhome.class).on(
                                    named("unhome").and(takesArguments(0)))))
                    .type(named("org.openpnp.machine.reference.ReferenceMachine"))
                    .transform((builder, type, classLoader, module, protectionDomain) -> builder
                            .visit(Advice.to(CoreXYAdvice.MachineHome.class).on(
                                    named("home")
                                            .and(isDeclaredBy(ReferenceMachine.class))
                                            .and(takesArguments(0))))
                            .visit(Advice.to(CoreXYAdvice.MachineHomed.class).on(
                                    named("setHomed")
                                            .and(isDeclaredBy(ReferenceMachine.class))
                                            .and(takesArguments(boolean.class)))))
                    .type(named("org.openpnp.machine.reference.ReferenceNozzle"))
                    .transform((builder, type, classLoader, module, protectionDomain) -> builder
                            .visit(Advice.to(CoreXYAdvice.ToolForward.class).on(
                                    named("toHeadLocation").and(takesArguments(3))))
                            .visit(Advice.to(CoreXYAdvice.ToolInverse.class).on(
                                    named("toHeadMountableLocation").and(takesArguments(3))))
                            .visit(Advice.to(CoreXYAdvice.ToolCamera.class).on(
                                    named("getCameraToolCalibratedOffset").and(takesArguments(1)))))
                    .installOn(instrumentation);
            installed = true;
        }

        configPath = requestedConfig;
        CoreXYRuntime.configure(configPath, version);
        return getStatus();
    }

    public static synchronized String reload() throws Exception {
        if (!installed || configPath == null) {
            throw new IllegalStateException("CoreXY Agent is not installed.");
        }
        CoreXYRuntime.configure(configPath, Main.getBuildString());
        return getStatus();
    }

    public static synchronized String loadTransient(String configuredPath) throws Exception {
        if (!installed || configPath == null) {
            throw new IllegalStateException("CoreXY Agent is not installed.");
        }
        if (Configuration.get().getMachine().getMotionPlanner().isHomed()) {
            throw new IllegalStateException(
                    "Transient CoreXY configuration requires an unhomed machine.");
        }
        Path transientPath = Paths.get(configuredPath).toAbsolutePath().normalize();
        CoreXYRuntime.configure(transientPath, Main.getBuildString());
        return getStatus();
    }

    public static synchronized String loadImmediate(String configuredPath) throws Exception {
        if (!installed || configPath == null) {
            throw new IllegalStateException("CoreXY Agent is not installed.");
        }
        Path immediatePath = Paths.get(configuredPath).toAbsolutePath().normalize();
        CoreXYRuntime.configureImmediate(immediatePath, Main.getBuildString());
        configPath = immediatePath;
        return getStatus();
    }

    public static String getStatus() {
        return CoreXYRuntime.status();
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static boolean hasNativeHooks() {
        try {
            return Boolean.TRUE.equals(AbstractMotionPlanner.class
                    .getMethod("hasNativeCoreXYHooks").invoke(null));
        }
        catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** Native Pro entry point; no attach API, external JDK or script is required. */
    public static synchronized String installNative(String configuredPath) throws Exception {
        if (!hasNativeHooks()) {
            throw new IllegalStateException("Native CoreXY hooks are not available.");
        }
        return install(null, configuredPath);
    }
}
