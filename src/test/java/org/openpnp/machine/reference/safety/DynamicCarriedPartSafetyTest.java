package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Part;
import org.openpnp.spi.base.AbstractNozzle;

class DynamicCarriedPartSafetyTest {
    @TempDir Path temporary;
    DynamicSafeMotionPlannerTest fixture;
    Part part;
    ReferenceNozzleTip tip;
    @BeforeEach void setup() throws Exception {
        fixture = new DynamicSafeMotionPlannerTest();
        fixture.temporary = temporary;
        fixture.fixture();
        part = new Part("C0603");
        org.openpnp.model.Package packag = new org.openpnp.model.Package("0603");
        packag.getFootprint().setBodyWidth(1.6);
        packag.getFootprint().setBodyHeight(.8);
        part.setPackage(packag);
        part.setHeight(new Length(0, LengthUnit.Millimeters));
        tip = new ReferenceNozzleTip();
        tip.setMaxPartHeight(new Length(2, LengthUnit.Millimeters));
        fixture.nozzle.setNozzleTip(tip);
        Field carried = AbstractNozzle.class.getDeclaredField("part");
        carried.setAccessible(true);
        carried.set(fixture.nozzle, part);
    }
    double carriedHeight() throws Exception {
        return DynamicSafetyCollisionEngine.snapshot(fixture.model, fixture.machine, fixture.nozzle).get(0).carriedPartHeight;
    }
    @Test void unknownHeightUsesConfiguredUpperBoundIncludingThroughBoardDepth() throws Exception {
        part.setThroughBoardDepth(new Length(3, LengthUnit.Millimeters));
        assertEquals(5, carriedHeight(), 1e-9);
        assertTrue(fixture.commands.isEmpty());
    }
    @Test void measuredHeightRetainsItsOwnEnvelope() throws Exception {
        part.setHeight(new Length(1.2, LengthUnit.Millimeters));
        part.setThroughBoardDepth(new Length(.3, LengthUnit.Millimeters));
        assertEquals(1.5, carriedHeight(), 1e-9);
    }
    @Test void unknownWithoutAValidUpperBoundAndNonfiniteDataAlwaysDecline() throws Exception {
        for (double height : new double[] {0, Double.NaN, Double.POSITIVE_INFINITY}) {
            tip.setMaxPartHeight(new Length(height, LengthUnit.Millimeters));
            assertThrows(Exception.class, this::carriedHeight);
            assertFalse(DynamicSafeMotionPlanner.preview(fixture.nozzle, fixture.target(), 1).isOptimized());
        }
        assertTrue(fixture.commands.isEmpty());
    }
    @Test void missingBodyDimensionsAreNeverReplacedByPadOrNozzleDimensions() throws Exception {
        part.getPackage().getFootprint().setBodyWidth(0);
        Exception failure = assertThrows(Exception.class, this::carriedHeight);
        assertTrue(failure.getMessage().contains("本体宽/长"));
    }
    @Test void pickAndPlaceStateDoesNotRequireRecertifyingTheSameGeometry() throws Exception {
        assertTrue(DynamicSafeMotionPlanner.preview(fixture.nozzle, fixture.target(), 1).isOptimized());
        fixture.nozzle.clearPartForRepick();
        assertTrue(DynamicSafeMotionPlanner.preview(fixture.nozzle, fixture.target(), 1).isOptimized());
        assertTrue(fixture.model.isCertified());
        assertEquals(0, carriedHeight());
        assertTrue(fixture.commands.isEmpty());
    }
}
