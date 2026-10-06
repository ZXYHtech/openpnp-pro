package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PartInterchangeabilityTest {
    @BeforeAll
    static void initializeConfiguration() {
        Configuration.initialize();
    }

    @Test
    void matchesExplicitAliasesAndPartIdsCaseInsensitively() {
        Part jobPart = new Part("JOB-R10K-0603");
        jobPart.setAliases("ERP-10086； customer-r10k");
        Part feederPart = new Part("erp-10086");

        assertTrue(jobPart.isInterchangeableWith(feederPart));
        assertTrue(feederPart.isInterchangeableWith(jobPart));
    }

    @Test
    void matchesPartsSharingACommonMaterialGroup() {
        Part jobPart = new Part("BOARD-A-R10K");
        jobPart.setDisplayId("R10K-0603-GROUP");
        Part feederPart = new Part("REEL-MPN-1234");
        feederPart.setDisplayId("r10k-0603-group");

        assertTrue(jobPart.isInterchangeableWith(feederPart));
    }

    @Test
    void variantAloneDoesNotMakeDifferentPartsInterchangeable() {
        Part first = new Part("R10K-1PCT");
        first.setVariant("0603");
        Part second = new Part("C10NF-50V");
        second.setVariant("0603");

        assertFalse(first.isInterchangeableWith(second));
    }

    @Test
    void unrelatedPartsRemainIncompatible() {
        Part first = new Part("R10K");
        Part second = new Part("R20K");

        assertTrue(first.isInterchangeableWith(first));
        assertFalse(first.isInterchangeableWith(second));
        assertFalse(first.isInterchangeableWith(null));
    }

    @Test
    void editingAGroupOrAliasImmediatelyRefreshesTheMatchingCache() {
        Part jobPart = new Part("TASK-PART");
        Part feederPart = new Part("FEEDER-PART");
        assertFalse(jobPart.isInterchangeableWith(feederPart));

        jobPart.setAliases("FEEDER-PART");
        assertTrue(jobPart.isInterchangeableWith(feederPart));

        jobPart.setAliases(null);
        jobPart.setDisplayId("COMMON-GROUP");
        feederPart.setDisplayId("common-group");
        assertTrue(jobPart.isInterchangeableWith(feederPart));
    }
}
