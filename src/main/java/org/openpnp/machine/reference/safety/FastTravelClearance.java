package org.openpnp.machine.reference.safety;

import java.util.ArrayList;
import java.util.List;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Head;
import org.openpnp.spi.Nozzle;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel.SafetyObject;

/** Conservative whole-plane admission check. Per-move swept validation remains mandatory. */
public final class FastTravelClearance {
    private FastTravelClearance() { }

    public static String reason(SafetyObject object, double floor) {
        if (restrictedZone(object)) { return "换嘴专用区：普通联动路径禁止进入（含上方），换嘴按原示教路径"; }
        double top = object.z + object.height;
        double missing = top + object.clearance - floor;
        return String.format(java.util.Locale.ROOT,
                "%s：顶面 Z=%.2f mm，间隙 %.2f mm，参考线 Z=%.2f mm；%s %.2f mm",
                object.name, top, object.clearance, floor,
                missing > 0.000001 ? "净空不足" : "已满足，剩余净空", Math.abs(missing));
    }

    public static boolean restrictedZone(SafetyObject object) {
        return object != null && object.type == SafetyWorkbenchModel.ObjectType.NOZZLE_CHANGER;
    }

    public static boolean blocksGlobalPlane(SafetyObject object, double floor) {
        // The full path validator still treats each changer as an enabled obstacle.
        return !restrictedZone(object) && exceeds(object, floor);
    }

    public static boolean exceeds(SafetyObject object, double floor) {
        return object != null && object.enabled && object.obstacle
                && (!Double.isFinite(object.z + object.height + object.clearance)
                    || object.z + object.height + object.clearance > floor + 0.000001);
    }

    public static List<String> check(SafetyWorkbenchModel model, Machine machine, double floor) {
        List<String> issues = new ArrayList<>(SafetyModelValidator.validate(model));
        if (!Double.isFinite(floor) || floor <= model.getMinZ() || floor >= model.getMaxZ()) {
            issues.add("参考高度不在有效 Z 范围内");
            return issues;
        }
        for (SafetyObject object : model.getObjects()) {
            if (blocksGlobalPlane(object, floor)) { issues.add(reason(object, floor)); }
        }
        int count = 0;
        try {
            for (Head head : machine.getHeads()) {
                for (Nozzle selected : head.getNozzles()) {
                    count++;
                    for (DynamicSafetyCollisionEngine.DynamicTool tool
                            : DynamicSafetyCollisionEngine.snapshot(model, machine, selected)) {
                        if (!tool.headId.equals(head.getId())) { continue; }
                        double z = tool.mappedZ != null ? tool.mappedZ.applyAsDouble(floor)
                                : tool.followsZ ? floor : tool.reference.z;
                        double bottom = tool.minimumZAt(z);
                        if (!Double.isFinite(bottom)) { throw new Exception("工具包络高度无效"); }
                        for (SafetyObject object : model.getObjects()) {
                            if (blocksGlobalPlane(object, bottom)) {
                                String issue = selected.getName() + " 在参考高度时，" + tool.name
                                        + " 的最低包络不能完全越过 " + object.name;
                                if (!issues.contains(issue)) { issues.add(issue); }
                            }
                        }
                    }
                }
            }
            if (count == 0) { issues.add("没有可检查的真实吸嘴"); }
        } catch (Exception failure) { issues.add("工具高度检查失败：" + failure.getMessage()); }
        return issues;
    }
}
