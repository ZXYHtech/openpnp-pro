package org.openpnp.packageimport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.openpnp.gui.importer.KicadFootprintNormalizer;
import org.openpnp.gui.importer.KicadFootprintParser;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Package;

public final class PackageImportPlan {
    public enum Decision {
        ADD_NEW("导入为新封装"), ALIAS_EXISTING("绑定为已有封装的别名"),
        NEW_VARIANT("作为新变体导入"), SKIP("不导入/忽略"),
        REPLACE_GEOMETRY("替换已有几何（高风险）");
        private final String label;
        Decision(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public static final class Item {
        public final KicadFootprintParser.Result parsed;
        public KicadFootprintNormalizer.Result normalized;
        public final PackageMatcher.Result match;
        public final PackageClassifier.Result classification;
        public Decision decision;
        public boolean selected = true;
        public double manualRotation;
        public String duplicateGroupId;
        public String error;

        Item(KicadFootprintParser.Result parsed, KicadFootprintNormalizer.Result normalized,
                PackageMatcher.Result match, PackageClassifier.Result classification) {
            this.parsed = parsed;
            this.normalized = normalized;
            this.match = match;
            this.classification = classification;
            this.decision = match.kind == PackageMatcher.Kind.NONE ? Decision.ADD_NEW
                    : match.canAutoBind() ? Decision.ALIAS_EXISTING : Decision.SKIP;
        }
        public String statusText() {
            if (error != null) { return "解析失败"; }
            if (duplicateGroupId != null) { return "疑似重复 " + duplicateGroupId; }
            if (match.kind == PackageMatcher.Kind.CONFLICT) { return "有冲突"; }
            if (match.canAutoBind()) { return "可复用已有封装"; }
            return normalized.needsReview || !normalized.warnings.isEmpty() ? "待审核" : "新增";
        }
        public void rotate(double degrees) {
            manualRotation = (manualRotation + degrees) % 360;
            normalized = KicadFootprintNormalizer.normalize(parsed, manualRotation);
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final String batchId = "KICAD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    public List<Item> getItems() { return items; }
    public String getBatchId() { return batchId; }

    public Item add(KicadFootprintParser.Result parsed, Configuration configuration) {
        KicadFootprintNormalizer.Result normalized = KicadFootprintNormalizer.normalize(parsed);
        Package candidate = createTransientPackage(parsed, normalized);
        PackageMatcher.Result match = PackageMatcher.match(parsed.name, normalized.footprint,
                configuration.getPackages());
        PackageClassifier.Result classification = PackageClassifier.classify(candidate);
        Item item = new Item(parsed, normalized, match, classification);
        items.add(item);
        regroupDuplicates();
        return item;
    }

    private Package createTransientPackage(KicadFootprintParser.Result parsed,
            KicadFootprintNormalizer.Result normalized) {
        Package pkg = new Package(parsed.name);
        pkg.setFootprint(normalized.footprint);
        pkg.setSourceReference(parsed.sourceFile == null ? parsed.name
                : parsed.sourceFile.getParentFile() == null ? parsed.sourceFile.getName()
                        : parsed.sourceFile.getParentFile().getName() + "/" + parsed.sourceFile.getName());
        return pkg;
    }

    private void regroupDuplicates() {
        Map<String, List<Item>> byFingerprint = new LinkedHashMap<>();
        for (Item item : items) {
            String fp = PackageGeometryFingerprint.compute(item.normalized.footprint);
            if (fp != null) { byFingerprint.computeIfAbsent(fp, key -> new ArrayList<>()).add(item); }
        }
        int group = 1;
        for (List<Item> values : byFingerprint.values()) { if (values.size() > 1) {
            String id = String.format("D%03d", group++);
            for (Item item : values) { item.duplicateGroupId = id; }
        } }
    }

    public String previewSummary() {
        int add=0, alias=0, variant=0, skip=0, replace=0;
        for (Item item : items) { if (item.selected) { switch(item.decision) {
            case ADD_NEW: add++;
            break;
            case ALIAS_EXISTING: alias++;
            break;
            case NEW_VARIANT: variant++;
            break;
            case REPLACE_GEOMETRY: replace++;
            break;
            default: skip++;
        } } }
        return "批次 " + batchId + "：新增 " + add + "，新变体 " + variant + "，加别名 "
                + alias + "，替换几何 " + replace + "，跳过 " + skip;
    }

    public String apply(Configuration configuration) throws Exception {
        validate();
        List<Package> added = new ArrayList<>();
        Map<Package, String> oldAliases = new HashMap<>();
        Map<Package, Footprint> oldFootprints = new HashMap<>();
        List<String> report = new ArrayList<>();
        String now = Instant.now().toString();
        try {
            for (Item item : items) {
                if (!item.selected || item.decision == Decision.SKIP) {
                    report.add("跳过：" + item.parsed.name);
                    continue;
                }
                Package existing = item.match.match;
                if (item.decision == Decision.ALIAS_EXISTING) {
                    oldAliases.put(existing, existing.getAliases());
                    PackageResolutionService.addAlias(existing, item.parsed.name);
                    report.add("别名绑定：" + item.parsed.name + " → " + existing.getId());
                    continue;
                }
                if (item.decision == Decision.REPLACE_GEOMETRY) {
                    oldFootprints.put(existing, existing.getFootprint());
                    existing.setFootprint(item.normalized.footprint);
                    updateMetadata(existing, item, now);
                    report.add("替换几何：" + existing.getId() + "（原设置和 Part 引用保留）");
                    continue;
                }
                String requested = PackageNameNormalizer.baseName(item.parsed.name);
                if (item.decision == Decision.NEW_VARIANT && existing != null)
                    { requested = existing.getId() + "-KICAD"; }
                String id = PackageResolutionService.uniqueId(configuration, requested);
                Package pkg = new Package(id);
                pkg.setFootprint(item.normalized.footprint);
                if (!id.equalsIgnoreCase(item.parsed.name)) { pkg.setAliases(item.parsed.name); }
                if (existing != null) { pkg.setRelatedPackageId(existing.getId()); }
                updateMetadata(pkg, item, now);
                configuration.addPackage(pkg);
                added.add(pkg);
                report.add("新增：" + pkg.getId());
            }
            return previewSummary() + "\n" + String.join("\n", report);
        }
        catch (Exception e) {
            for (Package pkg : added) { configuration.removePackage(pkg); }
            oldAliases.forEach(Package::setAliases);
            oldFootprints.forEach(Package::setFootprint);
            throw e;
        }
    }

    private void updateMetadata(Package pkg, Item item, String now) {
        pkg.setSourceType(Package.SourceType.KICAD);
        pkg.setSourceReference(item.parsed.sourceFile == null ? item.parsed.name
                : item.parsed.sourceFile.getName());
        pkg.setImportBatchId(batchId);
        pkg.setLastImportedAt(now);
        pkg.setCategoryId(item.classification.categoryId);
        pkg.setClassificationConfidence(item.classification.confidence);
        pkg.setClassificationReason(item.classification.reason);
        pkg.setOrientationAdjustment(item.normalized.rotation);
        pkg.setOrientationReason(item.normalized.reason);
        pkg.setGeometryFingerprint(PackageGeometryFingerprint.compute(item.normalized.footprint));
        pkg.setDuplicateGroupId(item.duplicateGroupId);
        if (item.duplicateGroupId != null) { pkg.setReviewState(Package.ReviewState.DUPLICATE_CANDIDATE); }
        else { if (item.match.kind == PackageMatcher.Kind.CONFLICT) { pkg.setReviewState(Package.ReviewState.CONFLICT); }
        else { if (item.normalized.needsReview || item.classification.confidence < .8
                || !item.normalized.warnings.isEmpty()) { pkg.setReviewState(Package.ReviewState.NEEDS_REVIEW); }
        else { pkg.setReviewState(Package.ReviewState.AUTO_CLASSIFIED); } } }
    }

    private void validate() {
        for (Item item : items) {
            if (!item.selected) { continue; }
            if ((item.decision == Decision.ALIAS_EXISTING
                    || item.decision == Decision.REPLACE_GEOMETRY) && item.match.match == null)
                { throw new IllegalStateException(item.parsed.name + " 没有可绑定的已有封装"); }
            if (item.decision == Decision.ALIAS_EXISTING && !item.match.difference.isEmpty())
                { throw new IllegalStateException(item.parsed.name + " 与已有几何不同，不能直接作为别名"); }
        }
    }
}
