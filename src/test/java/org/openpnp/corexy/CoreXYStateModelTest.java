package org.openpnp.corexy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CoreXYStateModelTest {
    @Test
    void mapsCartesianMovesToCoreXYStates() {
        CoreXYStateModel model = new CoreXYStateModel();
        model.update(100, 0, 15, 15);
        assertEquals(1, model.getAState());
        assertEquals(1, model.getBState());

        model.update(0, 100, 15, 15);
        assertEquals(1, model.getAState());
        assertEquals(-1, model.getBState());

        model.update(-100, 0, 15, 15);
        assertEquals(-1, model.getAState());
        assertEquals(-1, model.getBState());
    }

    @Test
    void ignoresSubThresholdMovementAndCanReset() {
        CoreXYStateModel model = new CoreXYStateModel();
        model.update(20, 0, 15, 15);
        model.update(-2, 1, 15, 15);
        assertEquals(1, model.getAState());
        assertEquals(1, model.getBState());

        model.reset();
        assertEquals(0, model.getAState());
        assertEquals(0, model.getBState());
    }

    @Test
    void handlesFortyFiveDegreeMovesIndependently() {
        CoreXYStateModel model = new CoreXYStateModel();
        model.update(50, 50, 15, 15);
        assertEquals(1, model.getAState());
        assertEquals(0, model.getBState());

        model.update(50, -50, 15, 15);
        assertEquals(1, model.getAState());
        assertEquals(1, model.getBState());
    }
}
