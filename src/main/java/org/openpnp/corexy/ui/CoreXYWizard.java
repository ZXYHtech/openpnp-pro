package org.openpnp.corexy.ui;

import java.awt.Frame;
import org.openpnp.model.Configuration;

/** Compatibility entry point; the retired plate wizard now opens the five-point workflow. */
public final class CoreXYWizard extends FixedMarkerDialog {
    public CoreXYWizard(Frame owner, Configuration configuration) throws Exception {
        super(owner, configuration);
    }

    static String layerNames(java.util.List<org.openpnp.corexy.precision.PrecisionModel.Layer> layers) {
        String[] names = {"公共偏置", "固定换向", "连续换向记忆", "A/B 联合状态", "几何误差", "空间网格",
                "速度与加速度", "温漂", "工具点", "换向状态空间网格"};
        java.util.List<String> selected = new java.util.ArrayList<>();
        for (org.openpnp.corexy.precision.PrecisionModel.Layer layer : layers) {
            selected.add(names[layer.ordinal()]);
        }
        return String.join(" + ", selected);
    }

}
