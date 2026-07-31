package org.openpnp.gui.support;

public enum JobUsageFilter {
    All("全部"),
    JobOnly("仅任务"),
    NonJobOnly("仅非任务");

    private final String label;

    JobUsageFilter(String label) {
        this.label = label;
    }

    public boolean includes(boolean usedByJob) {
        return this == All || (this == JobOnly && usedByJob)
                || (this == NonJobOnly && !usedByJob);
    }

    @Override
    public String toString() {
        return label;
    }
}
