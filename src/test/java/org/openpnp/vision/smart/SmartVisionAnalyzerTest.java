package org.openpnp.vision.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

class SmartVisionAnalyzerTest {
    @Test
    void measuresContrastAndChoosesAnExplainableTemplate() {
        BufferedImage image = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 100, 80);
        graphics.setColor(Color.BLACK);
        graphics.fillOval(30, 20, 40, 40);
        graphics.dispose();

        RoiAnalysis analysis = SmartVisionAnalyzer.analyze(image);

        assertEquals(100, analysis.getWidth());
        assertEquals(80, analysis.getHeight());
        assertTrue(analysis.getContrast() > 40);
        assertFalse(analysis.isLowContrast());
        assertEquals(SmartVisionTemplate.FIDUCIAL, SmartVisionTemplate.choose(analysis));
    }

    @Test
    void timeoutDiagnosisKeepsTheBestCandidateAndSuggestsNextActions() {
        RoiAnalysis analysis = new RoiAnalysis(80, 80, 35, 12, 0.03, 0.08);
        List<String> suggestions = SmartVisionDiagnosis.suggestions(analysis, 10, 0.91, true);

        assertTrue(suggestions.stream().anyMatch(value -> value.contains("亮度偏低")));
        assertTrue(suggestions.stream().anyMatch(value -> value.contains("总超时")));
        assertTrue(suggestions.stream().anyMatch(value -> value.contains("样本量偏少")));
    }
}
