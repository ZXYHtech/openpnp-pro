/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.vision.smart;

import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.MatchPartTemplate;
import org.openpnp.vision.pipeline.stages.MatchPartsTemplate;
import org.openpnp.vision.pipeline.stages.MatchTemplate;
import org.openpnp.vision.pipeline.stages.Threshold;

/** Conservative tuner that keeps every original stage, mask and custom parameter. */
public final class SmartVisionPipelineTuner {
    private SmartVisionPipelineTuner() {}

    public static CvPipeline tune(CvPipeline source, RoiAnalysis analysis,
            double confidence) throws Exception {
        if (source == null) {
            throw new IllegalArgumentException("当前视觉设置没有 Pipeline。");
        }
        // The smart studio also supports historical images without a configured
        // camera. CvPipeline.clone() resets external parameters and therefore
        // needs a camera for millimeter-to-pixel parameter stages. Snapshot the
        // currently visible stage values instead, then retain any prepared live
        // context for the online path.
        String sourceXml = source.getProperty("camera") == null
                ? source.toXmlStringPreservingRuntimeStageValues()
                : source.toXmlString();
        CvPipeline tuned = new CvPipeline(sourceXml);
        source.copyPropertiesTo(tuned);
        double threshold = Math.max(0.50, Math.min(0.995, confidence));
        for (CvStage stage : tuned.getStages()) {
            if (stage instanceof MatchTemplate) {
                MatchTemplate match = (MatchTemplate) stage;
                match.setThreshold(Math.max(match.getThreshold(), Math.min(0.95, threshold - 0.05)));
                match.setCorr(threshold);
            }
            else if (stage instanceof MatchPartTemplate) {
                ((MatchPartTemplate) stage).setThreshold(threshold);
            }
            else if (stage instanceof MatchPartsTemplate) {
                ((MatchPartsTemplate) stage).setThreshold(threshold);
            }
            else if (stage instanceof Threshold && analysis != null) {
                Threshold binary = (Threshold) stage;
                binary.setAuto(true);
            }
        }
        return tuned;
    }
}
