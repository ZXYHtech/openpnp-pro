package org.openpnp.packageimport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.openpnp.model.Package;

public final class PackageMatcher {
    public enum Kind { NONE, EXACT_ID, ALIAS, STRICT_NAME, GEOMETRY, LOOSE_CANDIDATE, CONFLICT }
    public static final class Result {
        public final Kind kind;
        public final Package match;
        public final List<Package> candidates;
        public final double confidence;
        public final String reason;
        public final String difference;
        Result(Kind kind, Package match, Collection<Package> candidates, double confidence,
                String reason, String difference) {
            this.kind = kind;
            this.match = match;
            this.candidates = new ArrayList<>(candidates);
            this.confidence = confidence;
            this.reason = reason;
            this.difference = difference;
        }
        public boolean canAutoBind() {
            return match != null && (kind == Kind.EXACT_ID || kind == Kind.ALIAS
                    || kind == Kind.STRICT_NAME || kind == Kind.GEOMETRY) && difference.isEmpty();
        }
    }

    private PackageMatcher() {}

    public static Result match(String rawName, org.openpnp.model.Footprint footprint,
            Collection<Package> existing) {
        String strict = PackageNameNormalizer.strict(rawName);
        String loose = PackageNameNormalizer.loose(rawName);
        String fingerprint = PackageGeometryFingerprint.compute(footprint);
        List<Package> exact = new ArrayList<>(), alias = new ArrayList<>(), normalized = new ArrayList<>(),
                geometry = new ArrayList<>(), looseMatches = new ArrayList<>();
        for (Package pkg : existing) {
            if (pkg.getId() != null && pkg.getId().equalsIgnoreCase(rawName)) { exact.add(pkg); }
            if (matchesNamed(pkg, strict)) { alias.add(pkg); }
            if (strict.equals(PackageNameNormalizer.strict(pkg.getId()))) { normalized.add(pkg); }
            String fp = pkg.getGeometryFingerprint();
            if (fp == null) { fp = PackageGeometryFingerprint.compute(pkg.getFootprint()); }
            if (fingerprint != null && fingerprint.equals(fp)) { geometry.add(pkg); }
            if (!loose.isEmpty() && loose.equals(PackageNameNormalizer.loose(pkg.getId()))) { looseMatches.add(pkg); }
        }
        Result result = unique(Kind.EXACT_ID, exact, .99, "ID 大小写不敏感精确匹配", footprint);
        if (result != null) { return result; }
        result = unique(Kind.ALIAS, alias, .97, "标准名或别名严格匹配", footprint);
        if (result != null) { return result; }
        result = unique(Kind.STRICT_NAME, normalized, .94, "去除库前缀、路径、扩展名和分隔符后唯一匹配", footprint);
        if (result != null) { return result; }
        result = unique(Kind.GEOMETRY, geometry, .92, "焊盘几何指纹精确匹配", footprint);
        if (result != null) { return result; }
        Set<Package> conflicts = new LinkedHashSet<>();
        conflicts.addAll(exact);
        conflicts.addAll(alias);
        conflicts.addAll(normalized);
        if (conflicts.size() > 1) { return new Result(Kind.CONFLICT, null, conflicts, .5,
                "名称匹配到多个已有封装，必须人工选择", "存在多个候选"); }
        if (!looseMatches.isEmpty()) { return new Result(Kind.LOOSE_CANDIDATE,
                looseMatches.size() == 1 ? looseMatches.get(0) : null, looseMatches, .55,
                "仅宽松名称相似，变体后缀可能代表不同尺寸", looseMatches.size() == 1
                        ? difference(looseMatches.get(0), footprint) : "存在多个宽松候选"); }
        return new Result(Kind.NONE, null, java.util.Collections.emptyList(), 0,
                "没有匹配的已有封装", "");
    }

    private static Result unique(Kind kind, List<Package> values, double confidence, String reason,
            org.openpnp.model.Footprint incoming) {
        if (values.size() == 1) {
            String diff = difference(values.get(0), incoming);
            return new Result(diff.isEmpty() ? kind : Kind.CONFLICT, values.get(0), values,
                    diff.isEmpty() ? confidence : .60, reason, diff);
        }
        if (values.size() > 1) { return new Result(Kind.CONFLICT, null, values, .5,
                reason + "，但候选不唯一", "存在多个候选"); }
        return null;
    }

    private static boolean matchesNamed(Package pkg, String strict) {
        if (strict.isEmpty()) { return false; }
        if (strict.equals(PackageNameNormalizer.strict(pkg.getStandardName()))) { return true; }
        if (pkg.getAliases() != null) { for (String a : pkg.getAliases().split("[,;\\r\\n]+"))
            { if (strict.equals(PackageNameNormalizer.strict(a))) { return true; } } }
        return false;
    }

    public static String difference(Package existing, org.openpnp.model.Footprint incoming) {
        if (incoming == null || incoming.getPads().isEmpty()) { return ""; }
        org.openpnp.model.Footprint old = existing.getFootprint();
        if (old == null || old.getPads().isEmpty()) { return "已有封装缺少焊盘，可在确认后补全"; }
        List<String> diffs = new ArrayList<>();
        if (old.getPads().size() != incoming.getPads().size())
            { diffs.add("焊盘数 " + old.getPads().size() + " → " + incoming.getPads().size()); }
        if (Math.abs(old.getBodyWidth() - incoming.getBodyWidth()) > .01
                || Math.abs(old.getBodyHeight() - incoming.getBodyHeight()) > .01)
            { diffs.add(String.format(Locale.ROOT, "本体 %.3f×%.3f → %.3f×%.3f",
                    old.getBodyWidth(), old.getBodyHeight(), incoming.getBodyWidth(), incoming.getBodyHeight())); }
        String a = PackageGeometryFingerprint.compute(old), b = PackageGeometryFingerprint.compute(incoming);
        if (a != null && b != null && !a.equals(b) && diffs.isEmpty()) { diffs.add("焊盘位置/尺寸/旋转不同"); }
        return String.join("；", diffs);
    }
}
