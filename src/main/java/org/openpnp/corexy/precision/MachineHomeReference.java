package org.openpnp.corexy.precision;

import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Head;
import org.openpnp.spi.base.AbstractHead;

/** Reads the configured optical datum, never the current camera position or mechanical axis zero. */
public final class MachineHomeReference {
    private MachineHomeReference() { }

    public static Location read(Configuration configuration) throws Exception {
        if (configuration.getMachine() == null || configuration.getMachine().getHeads().isEmpty()) {
            throw new IllegalStateException("请先配置机器的 Home 视觉基准。");
        }
        Head head = configuration.getMachine().getDefaultHead();
        if (!(head instanceof AbstractHead)
                || ((AbstractHead) head).getVisualHomingMethod() == AbstractHead.VisualHomingMethod.None) {
            throw new IllegalStateException("请先在机头设置中启用并配置 Home 视觉基准，再添加四个覆盖点。");
        }
        if (((AbstractHead) head).getVisualHomingMethod() != AbstractHead.VisualHomingMethod.ResetToFiducialLocation) {
            throw new IllegalStateException("自动读取 Home 需要视觉复位使用‘复位到基准位置’，请先核对机头设置。");
        }
        Location location = ((AbstractHead) head).getHomingFiducialLocation().convertToUnits(LengthUnit.Millimeters);
        if (!Double.isFinite(location.getX()) || !Double.isFinite(location.getY()) || !Double.isFinite(location.getZ())) {
            throw new IllegalStateException("机器的 Home 视觉基准坐标无效。");
        }
        return location;
    }
}
