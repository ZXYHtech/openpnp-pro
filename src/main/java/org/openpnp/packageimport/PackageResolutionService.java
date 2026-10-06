package org.openpnp.packageimport;

import java.time.Instant;

import org.openpnp.model.Configuration;
import org.openpnp.model.Package;

public final class PackageResolutionService {
    private PackageResolutionService() {}

    public static Package resolveOrCreatePlaceholder(Configuration configuration, String rawName,
            Package.SourceType sourceType, String sourceReference, String batchId) {
        PackageMatcher.Result match = PackageMatcher.match(rawName, null, configuration.getPackages());
        if (match.canAutoBind()) {
            addAlias(match.match, rawName);
            return match.match;
        }
        String base = PackageNameNormalizer.baseName(rawName);
        if (base.isEmpty()) { base = "UNNAMED-PACKAGE"; }
        String id = uniqueId(configuration, base);
        Package pkg = new Package(id);
        pkg.setAliases(rawName.equals(id) ? null : rawName);
        pkg.setSourceType(sourceType);
        pkg.setSourceReference(sourceReference);
        pkg.setImportBatchId(batchId);
        pkg.setLastImportedAt(Instant.now().toString());
        pkg.setReviewState(match.kind == PackageMatcher.Kind.CONFLICT
                || match.kind == PackageMatcher.Kind.LOOSE_CANDIDATE
                        ? Package.ReviewState.CONFLICT : Package.ReviewState.MISSING_FOOTPRINT);
        if (match.match != null) { pkg.setRelatedPackageId(match.match.getId()); }
        PackageClassifier.applySuggestion(pkg, false);
        pkg.setReviewState(match.kind == PackageMatcher.Kind.CONFLICT
                || match.kind == PackageMatcher.Kind.LOOSE_CANDIDATE
                        ? Package.ReviewState.CONFLICT : Package.ReviewState.MISSING_FOOTPRINT);
        configuration.addPackage(pkg);
        return pkg;
    }

    public static void initializeNewPackage(Package pkg, Package.SourceType sourceType,
            String sourceReference, String batchId) {
        pkg.setSourceType(sourceType);
        pkg.setSourceReference(sourceReference);
        pkg.setImportBatchId(batchId);
        PackageClassifier.applySuggestion(pkg, false);
    }

    public static void addAlias(Package pkg, String value) {
        if (value == null || value.trim().isEmpty()
                || value.equalsIgnoreCase(pkg.getId())) { return; }
        String aliases = pkg.getAliases();
        if (aliases != null) { for (String a : aliases.split("[,;\\r\\n]+"))
            { if (a.trim().equalsIgnoreCase(value.trim())) { return; } } }
        pkg.setAliases(aliases == null || aliases.trim().isEmpty() ? value.trim()
                : aliases + "; " + value.trim());
    }

    public static String uniqueId(Configuration configuration, String requested) {
        String id = requested;
        int index = 2;
        while (configuration.getPackage(id) != null) { id = requested + "-VAR" + index++; }
        return id;
    }
}
