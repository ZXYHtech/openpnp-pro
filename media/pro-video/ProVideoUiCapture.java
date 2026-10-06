import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import org.openpnp.gui.MainFrame;
import org.openpnp.gui.components.ThemeSettingsPanel;
import org.openpnp.gui.support.BackupRestoreDialog;
import org.openpnp.model.Configuration;

/**
 * Renders real OpenPnP Pro Swing pages for the feature video without touching a
 * user's live configuration or requiring desktop screen capture.
 */
public final class ProVideoUiCapture {
    private static final int WIDTH = 1600;
    private static final int HEIGHT = 900;

    private ProVideoUiCapture() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: ProVideoUiCapture <isolated-config-dir> <output-dir>");
        }

        Path configDir = Path.of(args[0]).toAbsolutePath();
        Path outputDir = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(configDir);
        Files.createDirectories(outputDir);

        checkpoint("initialize");
        Configuration.initialize(configDir.toFile());
        Configuration configuration = Configuration.get();
        Locale.setDefault(configuration.getLocale());
        checkpoint("theme");
        new ThemeSettingsPanel().setTheme(configuration.getThemeInfo(),
                configuration.getFontSize(), configuration.isAlternateRows());

        checkpoint("swing");
        SwingUtilities.invokeAndWait(() -> {
            try {
                checkpoint("frame");
                MainFrame frame = new MainFrame(configuration);
                frame.setSize(WIDTH, HEIGHT);
                frame.addNotify();
                layoutTree(frame.getRootPane());

                checkpoint("pages");
                capturePage(frame, frame.getJobTab(), outputDir.resolve("actual-workspace.png"));
                capturePage(frame, frame.getProductionDashboardTab(), outputDir.resolve("actual-dashboard.png"));
                capturePage(frame, frame.getPartsTab(), outputDir.resolve("actual-parts.png"));
                capturePage(frame, frame.getPackagesTab(), outputDir.resolve("actual-packages.png"));
                capturePage(frame, frame.getFeedersTab(), outputDir.resolve("actual-feeders.png"));
                capturePage(frame, frame.getBoardsTab(), outputDir.resolve("actual-boards.png"));

                checkpoint("backup");
                BackupRestoreDialog backupDialog = new BackupRestoreDialog(frame, configuration);
                backupDialog.setSize(1180, 680);
                backupDialog.addNotify();
                layoutTree(backupDialog.getRootPane());
                capture(backupDialog.getRootPane(), outputDir.resolve("actual-backup.png"),
                        backupDialog.getWidth(), backupDialog.getHeight());
                backupDialog.dispose();
                frame.dispose();
                checkpoint("done");
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.exit(0);
    }

    private static void checkpoint(String name) {
        System.err.println("[capture] " + name);
        System.err.flush();
    }

    private static void capturePage(MainFrame frame, Component page, Path output) throws Exception {
        frame.getTabs().setSelectedComponent(page);
        layoutTree(frame.getRootPane());
        capture(frame.getRootPane(), output, WIDTH, HEIGHT);
    }

    private static void layoutTree(Component component) {
        component.doLayout();
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                layoutTree(child);
            }
        }
    }

    private static void capture(JComponent component, Path output, int width, int height) throws Exception {
        component.setSize(width, height);
        layoutTree(component);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        component.printAll(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", output.toFile());
    }
}
