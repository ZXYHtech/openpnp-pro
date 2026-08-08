package org.openpnp.packageimport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PackageCategoryRegistry {
    public static final class Category {
        public final String id;
        public final String parentId;
        public final String label;

        Category(String id, String parentId, String label) {
            this.id = id;
            this.parentId = parentId;
            this.label = label;
        }
        public boolean isTopLevel() { return parentId == null; }
        @Override public String toString() { return isTopLevel() ? label : "　└ " + label; }
    }

    private static final List<Category> CATEGORIES = Collections.unmodifiableList(Arrays.asList(
            c("passive", null, "无源器件"),
            c("passive.resistor", "passive", "片式电阻"),
            c("passive.capacitor", "passive", "片式电容"),
            c("passive.inductor", "passive", "片式电感/磁珠"),
            c("passive.polarized-capacitor", "passive", "钽/电解电容"),
            c("passive.filter", "passive", "滤波器"),
            c("passive.transformer", "passive", "变压器"),
            c("discrete", null, "二极管与晶体管"),
            c("discrete.diode-led", "discrete", "二极管/LED"),
            c("discrete.sot", "discrete", "SOT/小型晶体管"),
            c("discrete.power", "discrete", "功率器件"),
            c("discrete.opto", "discrete", "光耦/光电器件"),
            c("ic", null, "集成电路"),
            c("ic.so", "ic", "SO/SOP/TSSOP/MSOP"),
            c("ic.qfn", "ic", "QFN/DFN/LGA"),
            c("ic.qfp", "ic", "QFP/LQFP/TQFP"),
            c("ic.bga", "ic", "BGA/CSP"),
            c("ic.dip", "ic", "DIP/通孔 IC"),
            c("connector", null, "连接器"),
            c("crystal", null, "晶振/振荡器"),
            c("electromechanical", null, "机电器件"),
            c("electromechanical.switch", "electromechanical", "开关/按键/编码器"),
            c("electromechanical.relay", "electromechanical", "继电器"),
            c("electromechanical.motor", "electromechanical", "电机/阀门"),
            c("electromechanical.buzzer", "electromechanical", "蜂鸣器"),
            c("sensor", null, "传感器/麦克风"),
            c("display", null, "显示器件"),
            c("protection", null, "保险丝/压敏器件"),
            c("rf", null, "射频/天线"),
            c("module", null, "模块与屏蔽罩"),
            c("mechanical", null, "机械件/定位标记"),
            c("mechanical.test-point", "mechanical", "测试点"),
            c("custom", null, "自定义")
    ));
    private static final Map<String, Category> BY_ID = new LinkedHashMap<>();
    static { for (Category category : CATEGORIES) { BY_ID.put(category.id, category); } }

    private PackageCategoryRegistry() {}
    private static Category c(String id, String parent, String label) {
        return new Category(id, parent, label);
    }
    public static List<Category> all() { return CATEGORIES; }
    public static Category get(String id) { return id == null ? null : BY_ID.get(id); }
    public static String label(String id) {
        Category c = get(id);
        return c == null ? "未分类" : c.label;
    }
    public static boolean includes(String filterId, String categoryId) {
        if (filterId == null) { return true; }
        if ("__uncategorized__".equals(filterId)) { return get(categoryId) == null; }
        if (filterId.equals(categoryId)) { return true; }
        Category actual = get(categoryId);
        return actual != null && filterId.equals(actual.parentId);
    }
    public static List<Category> childrenOf(String parentId) {
        List<Category> result = new ArrayList<>();
        for (Category c : CATEGORIES) { if (parentId.equals(c.parentId)) { result.add(c); } }
        return result;
    }
}
