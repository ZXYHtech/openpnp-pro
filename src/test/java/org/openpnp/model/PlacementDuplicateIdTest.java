package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

class PlacementDuplicateIdTest {
    @BeforeAll
    static void initializeConfiguration() {
        Configuration.initialize();
    }

    @Test
    void duplicateDisplayIdsReceiveStableUniqueInternalIds() {
        Board board = new Board();
        Placement first = new Placement("C1");
        Placement second = new Placement("C1");
        Placement third = new Placement("C1");

        board.addPlacement(first);
        board.addPlacement(second);
        board.addPlacement(third);

        assertEquals("C1", first.getId());
        assertEquals("C1#2", second.getId());
        assertEquals("C1#3", third.getId());
        assertEquals("C1", first.getDisplayId());
        assertEquals("C1", second.getDisplayId());
        assertEquals("C1", third.getDisplayId());
        assertSame(second, board.getPlacements().get("C1#2"));
    }

    @Test
    void legacyDuplicateIdsAreRepairedWithoutCollidingWithExistingSuffixes() {
        Board board = new Board();
        Placement first = new Placement("C1");
        Placement existingSuffix = new Placement("C1#2");
        Placement legacyDuplicate = new Placement("C1");
        board.getPlacements().add(first);
        board.getPlacements().add(existingSuffix);
        board.getPlacements().add(legacyDuplicate);

        assertEquals(1, board.ensureUniquePlacementIds());
        assertEquals("C1#3", legacyDuplicate.getId());
        assertEquals("C1", legacyDuplicate.getDisplayId());
        assertEquals("C1#2", existingSuffix.getDisplayId());
    }

    @Test
    void copiedPlacementsKeepDisplayAndInternalIdentitySeparate() {
        Board board = new Board();
        board.addPlacement(new Placement("R1"));
        board.addPlacement(new Placement("R1"));

        Board copy = new Board(board);
        Placement first = copy.getPlacement(0);
        Placement second = copy.getPlacement(1);

        assertEquals("R1", first.getDisplayId());
        assertEquals("R1", second.getDisplayId());
        assertNotEquals(first.getId(), second.getId());
    }

    @Test
    void repeatedDisplayIdsKeepIndependentPlacedStatus() {
        Board board = new Board();
        Placement first = new Placement("C1");
        Placement second = new Placement("C1");
        board.addPlacement(first);
        board.addPlacement(second);
        BoardLocation boardLocation = new BoardLocation(board);
        boardLocation.setId("Brd1");
        Job job = new Job();

        job.storePlacedStatus(boardLocation, second.getId(), true);

        assertFalse(job.retrievePlacedStatus(boardLocation, first.getId()));
        assertTrue(job.retrievePlacedStatus(boardLocation, second.getId()));
    }

    @Test
    void displayAndInternalIdsSurviveBoardSerialization() throws Exception {
        Board board = new Board();
        board.addPlacement(new Placement("C1"));
        board.addPlacement(new Placement("C1"));
        Serializer serializer = new Persister();
        StringWriter output = new StringWriter();

        serializer.write(board, output);
        Board restored = serializer.read(Board.class, new StringReader(output.toString()));

        assertEquals("C1", restored.getPlacement(0).getDisplayId());
        assertEquals("C1", restored.getPlacement(1).getDisplayId());
        assertEquals("C1", restored.getPlacement(0).getId());
        assertEquals("C1#2", restored.getPlacement(1).getId());
    }
}
