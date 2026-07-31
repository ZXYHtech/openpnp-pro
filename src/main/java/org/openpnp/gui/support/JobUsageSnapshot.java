package org.openpnp.gui.support;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.openpnp.model.BoardLocation;
import org.openpnp.model.Job;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;

/**
 * An intentionally frozen view of job usage. Editing table values does not change filtering until
 * the operator explicitly changes the usage display, or a different job is loaded.
 */
public final class JobUsageSnapshot {
    private final Set<String> partIds;
    private final Set<String> packageIds;
    private final Set<String> feederIds;

    private JobUsageSnapshot(Set<String> partIds, Set<String> packageIds,
            Set<String> feederIds) {
        this.partIds = partIds;
        this.packageIds = packageIds;
        this.feederIds = feederIds;
    }

    public static JobUsageSnapshot capture(Job job, Machine machine) {
        Set<String> partIds = new HashSet<>();
        Set<String> packageIds = new HashSet<>();
        if (job != null) {
            for (BoardLocation boardLocation : job.getBoardLocations()) {
                if (!boardLocation.isEnabled()) {
                    continue;
                }
                for (Placement placement : boardLocation.getBoard().getPlacements()) {
                    Part part = placement.getPart();
                    if (placement.getType() == Placement.Type.Placement && placement.isEnabled()
                            && part != null) {
                        partIds.add(part.getId());
                        if (part.getPackage() != null) {
                            packageIds.add(part.getPackage().getId());
                        }
                    }
                }
            }
        }
        Set<String> feederIds = new HashSet<>();
        if (machine != null) {
            for (Feeder feeder : machine.getFeeders()) {
                Part part = feeder.getPart();
                if (part != null && partIds.contains(part.getId())) {
                    feederIds.add(feeder.getId());
                }
            }
        }
        return new JobUsageSnapshot(Collections.unmodifiableSet(partIds),
                Collections.unmodifiableSet(packageIds), Collections.unmodifiableSet(feederIds));
    }

    public boolean isUsed(Part part) {
        return part != null && partIds.contains(part.getId());
    }

    public boolean isUsed(Package pkg) {
        return pkg != null && packageIds.contains(pkg.getId());
    }

    public boolean isUsed(Feeder feeder) {
        return feeder != null && feederIds.contains(feeder.getId());
    }
}
