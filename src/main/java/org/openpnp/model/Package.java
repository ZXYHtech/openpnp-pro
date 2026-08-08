/*
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openpnp.machine.reference.vision.AbstractPartSettingsHolder;
import org.openpnp.spi.NozzleTip;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Version;

public class Package extends AbstractPartSettingsHolder {
    public enum SourceType {
        BUILT_IN("内置"), MANUAL("手工"), CLIPBOARD("剪贴板"), BOM("BOM"),
        KICAD("KiCad"), LEGACY("旧配置");

        private final String label;
        SourceType(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum ReviewState {
        VERIFIED("已确认"), AUTO_CLASSIFIED("自动归类"), NEEDS_REVIEW("待审核"),
        DUPLICATE_CANDIDATE("疑似重复"), MISSING_FOOTPRINT("缺少封装信息"),
        CONFLICT("有冲突");

        private final String label;
        ReviewState(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    @Version(revision=1.1)
    private double version;    
    
    @Attribute
    private String id;

    @Attribute(required = false)
    private String description;

    @Attribute(required = false)
    private String standardName;

    /** Comma or semicolon separated standard, customer and legacy package names. */
    @Attribute(required = false)
    private String aliases;

    @Attribute(required = false)
    private String categoryId;

    @Attribute(required = false)
    private SourceType sourceType;

    @Attribute(required = false)
    private String sourceReference;

    @Attribute(required = false)
    private ReviewState reviewState;

    @Attribute(required = false)
    private String geometryFingerprint;

    @Attribute(required = false)
    private Double orientationAdjustment;

    @Attribute(required = false)
    private String orientationReason;

    @Attribute(required = false)
    private String lastReviewedAt;

    @Attribute(required = false)
    private String lastImportedAt;

    @Attribute(required = false)
    private String importBatchId;

    @Attribute(required = false)
    private String duplicateGroupId;

    @Attribute(required = false)
    private String relatedPackageId;

    @Attribute(required = false)
    private double classificationConfidence;

    @Attribute(required = false)
    private String classificationReason;

    @Attribute(required = false)
    private String tapeSpecification;

    @Attribute(required = false)
    private double pickVacuumLevel;

    @Attribute(required = false)
    private double placeBlowOffLevel;

    @Element(required = false)
    private Footprint footprint;
    
    @Element(required = false)
    private VisionCompositing visionCompositing;
    
    @ElementList(required = false)
    protected List<String> compatibleNozzleTipIds = new ArrayList<>();

    protected Set<NozzleTip> compatibleNozzleTips; 

    private Package() {
        this(null);
    }

    public Package(String id) {
        this.id = id;
        footprint = new Footprint();
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Warning: This should never be called once the Package is added to the configuration. It
     * should only be used when creating a new package.
     * 
     * @param id
     */
    public void setId(String id) {
        Object oldValue = this.id;
        this.id = id;
        firePropertyChange("id", oldValue, id);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        Object oldValue = this.description;
        this.description = description;
        firePropertyChange("description", oldValue, description);
    }

    public String getStandardName() {
        return standardName;
    }

    public void setStandardName(String standardName) {
        Object oldValue = this.standardName;
        this.standardName = standardName;
        firePropertyChange("standardName", oldValue, standardName);
    }

    public String getAliases() {
        return aliases;
    }

    public void setAliases(String aliases) {
        Object oldValue = this.aliases;
        this.aliases = aliases;
        firePropertyChange("aliases", oldValue, aliases);
    }

    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String value) {
        Object old = categoryId;
        categoryId = emptyToNull(value);
        firePropertyChange("categoryId", old, categoryId);
    }
    public SourceType getSourceType() { return sourceType == null ? SourceType.LEGACY : sourceType; }
    public void setSourceType(SourceType value) {
        Object old = sourceType;
        sourceType = value;
        firePropertyChange("sourceType", old, value);
    }
    public String getSourceReference() { return sourceReference; }
    public void setSourceReference(String value) {
        Object old = sourceReference;
        sourceReference = emptyToNull(value);
        firePropertyChange("sourceReference", old, sourceReference);
    }
    public ReviewState getReviewState() {
        if (reviewState != null) { return reviewState; }
        return footprint == null || footprint.getPads().isEmpty()
                ? ReviewState.MISSING_FOOTPRINT : ReviewState.NEEDS_REVIEW;
    }
    public void setReviewState(ReviewState value) {
        Object old = reviewState;
        reviewState = value;
        firePropertyChange("reviewState", old, value);
    }
    public String getGeometryFingerprint() { return geometryFingerprint; }
    public void setGeometryFingerprint(String value) {
        Object old = geometryFingerprint;
        geometryFingerprint = emptyToNull(value);
        firePropertyChange("geometryFingerprint", old, geometryFingerprint);
    }
    public double getOrientationAdjustment() {
        return orientationAdjustment == null ? 0.0 : orientationAdjustment;
    }
    public void setOrientationAdjustment(double value) {
        Object old = orientationAdjustment;
        orientationAdjustment = value;
        firePropertyChange("orientationAdjustment", old, value);
    }
    public String getOrientationReason() { return orientationReason; }
    public void setOrientationReason(String value) {
        Object old = orientationReason;
        orientationReason = emptyToNull(value);
        firePropertyChange("orientationReason", old, orientationReason);
    }
    public String getLastReviewedAt() { return lastReviewedAt; }
    public void setLastReviewedAt(String value) {
        Object old = lastReviewedAt;
        lastReviewedAt = emptyToNull(value);
        firePropertyChange("lastReviewedAt", old, lastReviewedAt);
    }
    public String getLastImportedAt() { return lastImportedAt; }
    public void setLastImportedAt(String value) {
        Object old = lastImportedAt;
        lastImportedAt = emptyToNull(value);
        firePropertyChange("lastImportedAt", old, lastImportedAt);
    }
    public String getImportBatchId() { return importBatchId; }
    public void setImportBatchId(String value) {
        Object old = importBatchId;
        importBatchId = emptyToNull(value);
        firePropertyChange("importBatchId", old, importBatchId);
    }
    public String getDuplicateGroupId() { return duplicateGroupId; }
    public void setDuplicateGroupId(String value) {
        Object old = duplicateGroupId;
        duplicateGroupId = emptyToNull(value);
        firePropertyChange("duplicateGroupId", old, duplicateGroupId);
    }
    public String getRelatedPackageId() { return relatedPackageId; }
    public void setRelatedPackageId(String value) {
        Object old = relatedPackageId;
        relatedPackageId = emptyToNull(value);
        firePropertyChange("relatedPackageId", old, relatedPackageId);
    }
    public double getClassificationConfidence() { return classificationConfidence; }
    public void setClassificationConfidence(double value) {
        double old = classificationConfidence;
        classificationConfidence = value;
        firePropertyChange("classificationConfidence", old, value);
    }
    public String getClassificationReason() { return classificationReason; }
    public void setClassificationReason(String value) {
        Object old = classificationReason;
        classificationReason = emptyToNull(value);
        firePropertyChange("classificationReason", old, classificationReason);
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public String getTapeSpecification() {
        return tapeSpecification;
    }

    public void setTapeSpecification(String tapeSpecification) {
        Object oldValue = this.tapeSpecification;
        this.tapeSpecification = tapeSpecification;
        firePropertyChange("tapeSpecification", oldValue, tapeSpecification);
    }

    public void setPlaceBlowOffLevel(double level) {
        Object oldValue = this.placeBlowOffLevel;
        this.placeBlowOffLevel = level;
        firePropertyChange("placeBlowOffLevel", oldValue, level);
    }

    public double getPlaceBlowOffLevel() {
        return placeBlowOffLevel;
    }

    public void setPickVacuumLevel(double level) {
        Object oldValue = this.pickVacuumLevel;
        this.pickVacuumLevel = level;
        firePropertyChange("pickVacuumLevel", oldValue, level);
    }

    public double getPickVacuumLevel() {
        return pickVacuumLevel;
    }

    public Footprint getFootprint() {
        return footprint;
    }

    public void setFootprint(Footprint footprint) {
        Object oldValue = this.footprint;
        this.footprint = footprint;
        firePropertyChange("footprint", oldValue, footprint);
    }

    public void fireFootprintChanged() {
        firePropertyChange("footprint", null, footprint); 
     }

    public VisionCompositing getVisionCompositing() {
        if (visionCompositing == null) {
            visionCompositing = new VisionCompositing();
        }
        return visionCompositing;
    }

    public void setVisionCompositing(VisionCompositing visionCompositing) {
        Object oldValue = this.visionCompositing;
        this.visionCompositing = visionCompositing;
        firePropertyChange("visionCompositing", oldValue, visionCompositing);
    }

    @Override
    public String toString() {
        return String.format("id %s", id);
    }
    
    protected void syncCompatibleNozzleTipIds() {
        compatibleNozzleTipIds.clear();
        for (NozzleTip nt : compatibleNozzleTips) {
            compatibleNozzleTipIds.add(nt.getId());
        }
    }
    
    public Set<NozzleTip> getCompatibleNozzleTips() {
        if (compatibleNozzleTips == null) {
            compatibleNozzleTips = new HashSet<>();
            for (String nozzleTipId : compatibleNozzleTipIds) {
                NozzleTip nt = Configuration.get().getMachine().getNozzleTip(nozzleTipId);
                if (nt != null) {
                    compatibleNozzleTips.add(nt);
                }
            }
        }
        return Collections.unmodifiableSet(compatibleNozzleTips);
    }

    public void addCompatibleNozzleTip(NozzleTip nt) {
        // Makes sure the structure has been initialized.
        getCompatibleNozzleTips();
        compatibleNozzleTips.add(nt);
        syncCompatibleNozzleTipIds();
        firePropertyChange("compatibleNozzleTips", null, getCompatibleNozzleTips());
    }

    public void removeCompatibleNozzleTip(NozzleTip nt) {
        // Makes sure the structure has been initialized.
        getCompatibleNozzleTips();
        compatibleNozzleTips.remove(nt);
        syncCompatibleNozzleTipIds();
        firePropertyChange("compatibleNozzleTips", null, getCompatibleNozzleTips());
    }
}
