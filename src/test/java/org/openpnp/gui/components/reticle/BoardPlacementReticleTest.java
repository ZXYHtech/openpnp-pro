package org.openpnp.gui.components.reticle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Abstract2DLocatable.Side;

class BoardPlacementReticleTest {
    @Test
    void mapsMachineCoordinatesIntoCameraPixels() {
        AffineTransform transform = BoardPlacementReticle.worldToViewTransform(
                100, 50, 0.1, 0.1, 200, 150);

        Point2D point = transform.transform(new Point2D.Double(110, 45), null);

        assertEquals(300, point.getX(), 0.0001);
        assertEquals(200, point.getY(), 0.0001);
    }

    @Test
    void rotatesAndMirrorsFootprintsInMachineCoordinates() {
        AffineTransform top = BoardPlacementReticle.placementTransform(
                10, 20, 90, 1, false);
        Point2D topPoint = top.transform(new Point2D.Double(1, 0), null);
        assertEquals(10, topPoint.getX(), 0.0001);
        assertEquals(21, topPoint.getY(), 0.0001);

        AffineTransform bottom = BoardPlacementReticle.placementTransform(
                10, 20, 0, 1, true);
        Point2D bottomPoint = bottom.transform(new Point2D.Double(1, 0), null);
        assertEquals(9, bottomPoint.getX(), 0.0001);
        assertEquals(20, bottomPoint.getY(), 0.0001);
    }

    @Test
    void preservesViewportZoomWhileUsingBoardHeightScale() {
        assertEquals(0.16, BoardPlacementReticle.scaledUnitsForBoard(
                0.20, 0.10, 0.08), 0.0001);
    }

    @Test
    void projectsOnlyTheBoardSideFacingTheTopCamera() {
        assertEquals(true, BoardPlacementReticle.isVisiblePlacement(Side.Top, Side.Top));
        assertEquals(true, BoardPlacementReticle.isVisiblePlacement(Side.Bottom, Side.Bottom));
        assertEquals(false, BoardPlacementReticle.isVisiblePlacement(Side.Bottom, Side.Top));
    }
}
