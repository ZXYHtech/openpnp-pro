package org.openpnp.corexy.validation;

import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

import org.openpnp.Main;
import org.openpnp.corexy.CoreXYIntegration;
import org.openpnp.corexy.agent.CoreXYAgent;
import org.openpnp.corexy.precision.PrecisionRuntime;
import org.openpnp.corexy.ui.CoreXYWizard;
import org.openpnp.gui.MainFrame;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Driver;

/** Real packaged GUI and old toolbox, using isolated XML, memory preferences and disabled NullDriver. */
public final class ProPackageSmoke {
    public static void main(String[] arguments) throws Exception {
        System.setProperty("java.util.prefs.PreferencesFactory", MemoryFactory.class.getName());
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(60000);
                System.err.println("PRO SMOKE timed out");
                Runtime.getRuntime().halt(2);
            }
            catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            Path config = java.nio.file.Paths.get(arguments[0]);
            Files.createDirectories(config);
            Path temporary = config.resolve("tmp");
            Files.createDirectories(temporary);
            System.setProperty("java.io.tmpdir", temporary.toString());
            Configuration.initialize(config.toFile());
            Configuration configuration = Configuration.get();
            configuration.load();
            require(!configuration.getMachine().isEnabled(), "Machine must remain disconnected");
            for (Driver driver : configuration.getMachine().getDrivers()) {
                require(driver instanceof NullDriver, "Only a virtual driver is permitted in this test");
            }
            org.openpnp.machine.reference.camera.ReferenceCamera camera =
                    (org.openpnp.machine.reference.camera.ReferenceCamera) configuration.getMachine().getDefaultHead().getDefaultCamera();
            Driver driver = configuration.getMachine().getDrivers().get(0);
            ReferenceControllerAxis x = axis(Axis.Type.X, driver), y = axis(Axis.Type.Y, driver);
            configuration.getMachine().addAxis(x);
            configuration.getMachine().addAxis(y);
            camera.setAxisX(x);
            camera.setAxisY(y);
            configuration.save();
            // MainFrame owns configuration.load(), after its listeners and status labels exist.
            Configuration.initialize(config.toFile());
            Configuration uiConfiguration = Configuration.get();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    new org.openpnp.gui.components.ThemeSettingsPanel().setTheme(uiConfiguration.getThemeInfo(),
                            uiConfiguration.getFontSize(), uiConfiguration.isAlternateRows());
                    MainFrame frame = new MainFrame(uiConfiguration);
                    CoreXYIntegration.initialize(uiConfiguration);
                    require(CoreXYAgent.hasNativeHooks() && CoreXYAgent.isInstalled(), "Packaged native integration must initialize");
                    require(CoreXYAgent.getStartupInstrumentation() == null, "Native Pro must not require an agent or attach");
                    require(!PrecisionRuntime.isActive(), "Fresh profile must not enable compensation");
                    frame.addNotify();
                    frame.validate();
                    require(find(frame.getJMenuBar(), "CoreXY 自动精度校准"), "Experimental menu entry must exist");
                    CoreXYWizard wizard = new CoreXYWizard(frame, uiConfiguration);
                    wizard.addNotify();
                    wizard.validate();
                    require(wizard.getWidth() >= 880, "Wizard layout must be usable");
                    org.openpnp.corexy.ui.FixedMarkerDialog fixed = new org.openpnp.corexy.ui.FixedMarkerDialog(wizard, uiConfiguration);
                    fixed.addNotify();
                    fixed.validate();
                    require(fixed.getWidth() >= 880, "Five-marker layout must be usable");
                    require(!PrecisionRuntime.isActive(), "Opening fixed marker setup must not enable production compensation");
                    fixed.dispose();
                    wizard.dispose();
                    CoreXYIntegration.launchLegacy(uiConfiguration);
                    boolean original = false;
                    for (Window window : Window.getWindows()) {
                        if (window instanceof JFrame && window != frame
                                && ((JFrame) window).getTitle().contains("CoreXY")) {
                            original = true;
                        }
                    }
                    require(original, "Complete original Jython toolbox must construct successfully");
                    require(!uiConfiguration.getMachine().isEnabled(), "GUI initialization must not connect hardware");
                }
                catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            // Drain delayed configuration listeners before closing their cameras and windows.
            SwingUtilities.invokeAndWait(() -> { });
            uiConfiguration.getMachine().close();
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    window.dispose();
                }
            });
            System.out.println("PRO PACKAGE PASS: " + Main.getVersionString()
                    + "; native hooks, experimental menu, five-marker UI, new wizard and complete original toolbox; no attach, no hardware.");
            System.exit(0);
        }
        catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
    private static ReferenceControllerAxis axis(Axis.Type type, Driver driver) {
        ReferenceControllerAxis axis = new ReferenceControllerAxis();
        axis.setType(type);
        axis.setDriver(driver);
        axis.setLetter(type.name());
        axis.setSoftLimitLow(new Length(0, LengthUnit.Millimeters));
        axis.setSoftLimitHigh(new Length(300, LengthUnit.Millimeters));
        axis.setSoftLimitLowEnabled(true);
        axis.setSoftLimitHighEnabled(true);
        axis.setBacklashCompensationMethod(ReferenceControllerAxis.BacklashCompensationMethod.None);
        return axis;
    }
    private static boolean find(java.awt.Container container, String text) {
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof JMenuItem && ((JMenuItem) component).getText().contains(text)) {
                return true;
            }
            if (component instanceof JMenu && find(((JMenu) component).getPopupMenu(), text)) {
                return true;
            }
            if (component instanceof java.awt.Container && find((java.awt.Container) component, text)) {
                return true;
            }
        }
        return false;
    }
    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
    public static final class MemoryFactory implements PreferencesFactory {
        private final Preferences root = new MemoryPreferences(null, "");
        public Preferences userRoot() {
            return root;
        }
        public Preferences systemRoot() {
            return root;
        }
    }
    private static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, MemoryPreferences> children = new HashMap<>();
        MemoryPreferences(AbstractPreferences parent, String name) {
            super(parent, name);
        }
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }
        protected String getSpi(String key) {
            return values.get(key);
        }
        protected void removeSpi(String key) {
            values.remove(key);
        }
        protected void removeNodeSpi() {
            values.clear();
            children.clear();
        }
        protected String[] keysSpi() {
            return values.keySet().toArray(new String[0]);
        }
        protected String[] childrenNamesSpi() {
            return children.keySet().toArray(new String[0]);
        }
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, value -> new MemoryPreferences(this, value));
        }
        protected void syncSpi() {
        }
        protected void flushSpi() {
        }
    }
}
