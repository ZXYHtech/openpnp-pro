package org.openpnp.packageimport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.openpnp.model.Package;

/** Rebuilds classification suggestions and duplicate groups without merging or deleting packages. */
public final class PackageLibraryAuditService {
    public static final class Result {
        public int analyzed;
        public int classified;
        public int duplicateGroups;
        public int duplicatePackages;
        public int missingFootprints;
    }

    private PackageLibraryAuditService() {
    }

    public static Result audit(Collection<Package> allPackages,
            Collection<Package> classificationTargets) {
        Result result = new Result();
        for (Package pkg : classificationTargets) {
            String before = pkg.getCategoryId();
            PackageClassifier.applySuggestion(pkg, true);
            result.analyzed++;
            if (!Objects.equals(before, pkg.getCategoryId())) {
                result.classified++;
            }
        }

        Map<String, List<Package>> groups = new LinkedHashMap<>();
        for (Package pkg : allPackages) {
            if (pkg.getReviewState() == Package.ReviewState.DUPLICATE_CANDIDATE) {
                pkg.setReviewState(defaultReviewState(pkg));
            }
            pkg.setDuplicateGroupId(null);
            String fingerprint = PackageGeometryFingerprint.compute(pkg.getFootprint());
            pkg.setGeometryFingerprint(fingerprint);
            if (fingerprint == null) {
                result.missingFootprints++;
                if (pkg.getReviewState() != Package.ReviewState.VERIFIED
                        && pkg.getReviewState() != Package.ReviewState.CONFLICT) {
                    pkg.setReviewState(Package.ReviewState.MISSING_FOOTPRINT);
                }
            }
            else {
                groups.computeIfAbsent(fingerprint, ignored -> new ArrayList<>()).add(pkg);
            }
        }

        int groupIndex = 1;
        for (List<Package> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            String groupId = String.format("D%03d", groupIndex++);
            result.duplicateGroups++;
            result.duplicatePackages += group.size();
            for (Package pkg : group) {
                pkg.setDuplicateGroupId(groupId);
                if (pkg.getReviewState() != Package.ReviewState.VERIFIED
                        && pkg.getReviewState() != Package.ReviewState.CONFLICT) {
                    pkg.setReviewState(Package.ReviewState.DUPLICATE_CANDIDATE);
                }
            }
        }
        return result;
    }

    private static Package.ReviewState defaultReviewState(Package pkg) {
        if (pkg.getFootprint() == null || pkg.getFootprint().getPads().isEmpty()) {
            return Package.ReviewState.MISSING_FOOTPRINT;
        }
        if (pkg.getCategoryId() != null && pkg.getClassificationConfidence() >= .8) {
            return Package.ReviewState.AUTO_CLASSIFIED;
        }
        return Package.ReviewState.NEEDS_REVIEW;
    }
}
