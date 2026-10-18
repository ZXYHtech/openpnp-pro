package org.openpnp.gui.support;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.feeder.ReferenceAutoFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.Part;

class FeederMaterialSaveTest {
    @TempDir Path temporary;
    @Test void newlyCreatedMaterialAndFeederReferenceAreBothDurableWithoutSavingBoardFiles() throws Exception {
        Configuration.initialize(temporary.toFile());
        Configuration configuration = Configuration.get();
        ReferenceMachine machine = new ReferenceMachine();
        configuration.setMachine(machine);
        org.openpnp.model.Package footprint = new org.openpnp.model.Package("New-0603");
        configuration.addPackage(footprint);
        Part part = new Part("New-10K");
        part.setPackage(footprint);
        configuration.addPart(part);
        ReferenceAutoFeeder feeder = new ReferenceAutoFeeder();
        feeder.setPart(part);
        machine.addFeeder(feeder);
        configuration.saveFeederConfiguration();
        assertTrue(Files.readString(temporary.resolve("parts.xml")).contains("New-10K"));
        assertTrue(Files.readString(temporary.resolve("packages.xml")).contains("New-0603"));
        assertTrue(Files.readString(temporary.resolve("machine.xml")).contains("New-10K"));
        assertFalse(Files.exists(temporary.resolve("boards.xml")), "Feeder save must not visit unrelated board files");
    }
}
