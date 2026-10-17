package org.openpnp.corexy.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.CancellationException;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.openpnp.corexy.precision.PrecisionMetrics;
import org.openpnp.corexy.precision.PrecisionProfile;
import org.openpnp.corexy.precision.PrecisionSample;
import org.openpnp.corexy.precision.StartupVerificationReport;

class CalibrationResultPresentationTest {
    private PrecisionMetrics metrics(double error) {
        return PrecisionMetrics.evaluate(List.of(new PrecisionSample("test", "camera", 0, 0, 0, 0,
                .6, 10000, Double.NaN, 0, 0, 0, 0, 0, 1000, 0, 0, error, 0)), null);
    }

    private PrecisionProfile profile() {
        PrecisionProfile profile = new PrecisionProfile();
        profile.createdAt = System.currentTimeMillis();
        profile.target = .05;
        profile.baseline = metrics(.1);
        profile.corrected = metrics(.03);
        return profile;
    }

    @Test
    void improvementAboveTargetIsAcceptedWithAnExplicitGoalNotice() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CalibrationCelebrationPanel panel = new CalibrationCelebrationPanel();
            StartupVerificationReport report = new StartupVerificationReport();
            report.passed = true;
            report.target = .05;
            report.metrics = metrics(.06);
            CalibrationResultPresentation.verification(panel, report, profile(), true);
            assertEquals(true, panel.getClientProperty("result.success"));
            assertEquals(false, panel.getClientProperty("result.unhappy"));
            assertTrue(text(panel).contains("补偿有效"));
            assertTrue(text(panel).contains("未达到（目标仅作提示）"));
        });
    }

    @Test
    void failedCurrentAttemptCannotBeOverwrittenByGoodSavedProfile() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CalibrationCelebrationPanel panel = new CalibrationCelebrationPanel();
            panel.showProfile(profile(), true, false);
            CalibrationResultPresentation.failure(panel, new IllegalStateException("路径超出软限位"));
            panel.showProfile(profile(), true, true);
            assertEquals(false, panel.getClientProperty("result.success"));
            assertTrue(text(panel).contains("不要关闭软限位"));
            assertFalse(text(panel).contains("已保存方案达到设定目标"));
        });
    }

    @Test
    void failedAcceptanceCannotBeReportedAsSuccessEvenWithSmallP95() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CalibrationCelebrationPanel panel = new CalibrationCelebrationPanel();
            StartupVerificationReport report = new StartupVerificationReport();
            report.target = .05;
            report.metrics = metrics(.02);
            report.passed = false;
            CalibrationResultPresentation.verification(panel, report, profile(), false);
            assertEquals(false, panel.getClientProperty("result.success"));
            assertTrue(text(panel).contains("这次校验没有通过"));
        });
    }

    @Test
    void cancellationIsNeutralAndMissingDataIsNotAZeroErrorResult() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CalibrationCelebrationPanel panel = new CalibrationCelebrationPanel();
            CalibrationResultPresentation.failure(panel, new CancellationException("cancel"));
            assertEquals(false, panel.getClientProperty("result.success"));
            assertEquals(false, panel.getClientProperty("result.unhappy"));
            assertTrue(text(panel).contains("没有完整精度结果"));
        });
    }

    private String text(Component component) {
        StringBuilder text = new StringBuilder(component instanceof JLabel ? ((JLabel) component).getText() : "");
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) { text.append(text(child)); }
        }
        return text.toString();
    }
}
