/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.components.reticle;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Placement;
import org.openpnp.spi.Camera;

/** A world-fixed target used while measuring the actual center of a trial placement. */
public class TrialPlacementReticle implements Reticle {
    private static final Color TARGET = new Color(0x46F2A0);
    private static final Color WARNING = new Color(0xFFCB4C);

    private final Camera camera;
    private final String placementId;
    private final Location nominal;
    private final Shape body;
    private final Shape pads;

    public TrialPlacementReticle(Camera camera, Placement placement, Location nominal,
            Side boardSide) {
        this.camera = camera;
        this.placementId = placement.getDisplayId();
        this.nominal = nominal.convertToUnits(LengthUnit.Millimeters);
        Footprint footprint = placement.getPart() == null
                || placement.getPart().getPackage() == null ? null
                        : placement.getPart().getPackage().getFootprint();
        Shape localBody = footprint == null ? new Rectangle2D.Double(-1, -1, 2, 2)
                : footprint.getBodyShape();
        Shape localPads = footprint == null ? new Rectangle2D.Double()
                : footprint.getPadsShape();
        double scale = footprint == null ? 1.0
                : new Length(1, footprint.getUnits())
                        .convertToUnits(LengthUnit.Millimeters).getValue();
        AffineTransform transform = BoardPlacementReticle.placementTransform(
                this.nominal.getX(), this.nominal.getY(), this.nominal.getRotation(), scale,
                boardSide == Side.Bottom);
        body = transform.createTransformedShape(localBody);
        pads = transform.createTransformedShape(localPads);
    }

    @Override
    public void draw(Graphics2D graphics, LengthUnit cameraUnitsPerPixelUnits,
            double cameraUnitsPerPixelX, double cameraUnitsPerPixelY,
            double viewPortCenterX, double viewPortCenterY, int viewPortWidth,
            int viewPortHeight, double rotation) {
        Location cameraLocation = camera.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location currentUpp = camera.getUnitsPerPixelAtZ().convertToUnits(LengthUnit.Millimeters);
        Location targetUpp = camera.getUnitsPerPixel(
                new Length(nominal.getZ(), LengthUnit.Millimeters))
                .convertToUnits(LengthUnit.Millimeters);
        double scaledX = new Length(cameraUnitsPerPixelX, cameraUnitsPerPixelUnits)
                .convertToUnits(LengthUnit.Millimeters).getValue();
        double scaledY = new Length(cameraUnitsPerPixelY, cameraUnitsPerPixelUnits)
                .convertToUnits(LengthUnit.Millimeters).getValue();
        double uppX = BoardPlacementReticle.scaledUnitsForBoard(scaledX,
                currentUpp.getX(), targetUpp.getX());
        double uppY = BoardPlacementReticle.scaledUnitsForBoard(scaledY,
                currentUpp.getY(), targetUpp.getY());
        if (!Double.isFinite(uppX) || !Double.isFinite(uppY) || uppX <= 0 || uppY <= 0) {
            return;
        }
        AffineTransform worldToView = BoardPlacementReticle.worldToViewTransform(
                cameraLocation.getX(), cameraLocation.getY(), uppX, uppY,
                viewPortCenterX, viewPortCenterY);
        Point2D center = worldToView.transform(
                new Point2D.Double(nominal.getX(), nominal.getY()), null);

        Graphics2D g2d = (Graphics2D) graphics.create();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Composite original = g2d.getComposite();
            // Keep the footprint only as a faint size/orientation hint. The real component and
            // fixed center cross must remain visually dominant during manual alignment.
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.04f));
            g2d.setColor(TARGET);
            g2d.fill(worldToView.createTransformedShape(body));
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.08f));
            g2d.fill(worldToView.createTransformedShape(pads));
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
            g2d.setStroke(new BasicStroke(1f));
            g2d.draw(worldToView.createTransformedShape(body));
            g2d.draw(worldToView.createTransformedShape(pads));
            g2d.setComposite(original);
            g2d.setStroke(new BasicStroke(1.5f));
            g2d.setColor(TARGET);
            g2d.draw(new Line2D.Double(center.getX() - 18, center.getY(),
                    center.getX() + 18, center.getY()));
            g2d.draw(new Line2D.Double(center.getX(), center.getY() - 18,
                    center.getX(), center.getY() + 18));
            g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 13f));
            g2d.setColor(WARNING);
            g2d.drawString("固定理论中心 · " + placementId + "（移动相机对准实物中心）",
                    (float) (viewPortCenterX - viewPortWidth / 2.0 + 12),
                    (float) (viewPortCenterY - viewPortHeight / 2.0 + 24));
        }
        finally {
            g2d.dispose();
        }
    }
}
