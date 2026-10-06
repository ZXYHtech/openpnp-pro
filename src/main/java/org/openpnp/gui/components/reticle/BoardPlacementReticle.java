/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.gui.components.reticle;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Footprint;
import org.openpnp.model.Job;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Placement;
import org.openpnp.spi.Camera;
import org.openpnp.util.Utils2D;

/**
 * Projects the selected board's placement footprints into a live top-camera view.
 * Board geometry is cached while the camera position is read for every frame.
 */
public class BoardPlacementReticle implements Reticle {
    private static final Color BOARD_COLOR = new Color(0x5ED0FF);
    private static final Color UNPLACED_COLOR = new Color(0x45D9FF);
    private static final Color PLACED_COLOR = new Color(0x42E08A);
    private static final Color DISABLED_COLOR = new Color(0x8C96A3);
    private static final Color FIDUCIAL_COLOR = new Color(0xFFB84A);
    private static final Color MISSING_COLOR = new Color(0xFF6B5C);
    private static final int LABEL_VISIBLE_LIMIT = 40;

    private final Camera camera;
    private final BoardLocation boardLocation;
    private final Job job;
    private volatile Snapshot snapshot;

    public BoardPlacementReticle(Camera camera, BoardLocation boardLocation, Job job) {
        this.camera = camera;
        this.boardLocation = boardLocation;
        this.job = job;
        refresh();
    }

    public final void refresh() {
        Location board = boardLocation.getGlobalLocation().convertToUnits(LengthUnit.Millimeters);
        AffineTransform boardTransform = boardLocation.getLocalToGlobalTransform();
        Shape profile = boardLocation.getBoard().getProfile()
                .convertToUnits(LengthUnit.Millimeters);
        profile = boardTransform.createTransformedShape(profile);
        Point2D origin = boardTransform.transform(new Point2D.Double(0, 0), null);
        Point2D xAxis = boardTransform.transform(new Point2D.Double(5, 0), null);
        Point2D yAxis = boardTransform.transform(new Point2D.Double(0, 5), null);

        List<ProjectedPlacement> placements = new ArrayList<>();
        for (Placement placement : boardLocation.getBoard().getPlacements()) {
            if (!isVisiblePlacement(placement.getSide(), boardLocation.getGlobalSide())) {
                continue;
            }
            Location location = Utils2D.calculateBoardPlacementLocation(boardLocation, placement)
                    .convertToUnits(LengthUnit.Millimeters);
            placements.add(createProjectedPlacement(placement, location,
                    boardLocation.getGlobalSide() == Side.Bottom));
        }
        String name = boardLocation.getUniqueId();
        if (name == null || name.trim().isEmpty()) {
            name = boardLocation.getBoard().getName();
        }
        if (name == null || name.trim().isEmpty()) {
            name = "当前单板";
        }
        snapshot = new Snapshot(name, board.getZ(), profile, origin, xAxis, yAxis,
                Collections.unmodifiableList(placements));
    }

    public int getPlacementCount() {
        return snapshot.placements.size();
    }

    public String getBoardName() {
        return snapshot.boardName;
    }

    @Override
    public void draw(Graphics2D graphics, LengthUnit cameraUnitsPerPixelUnits,
            double cameraUnitsPerPixelX, double cameraUnitsPerPixelY,
            double viewPortCenterX, double viewPortCenterY, int viewPortWidth,
            int viewPortHeight, double rotation) {
        Snapshot current = snapshot;
        Location cameraLocation = camera.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location currentUnitsPerPixel = camera.getUnitsPerPixelAtZ()
                .convertToUnits(LengthUnit.Millimeters);
        Location boardUnitsPerPixel = camera.getUnitsPerPixel(
                new Length(current.boardZ, LengthUnit.Millimeters))
                .convertToUnits(LengthUnit.Millimeters);
        double scaledX = new Length(cameraUnitsPerPixelX, cameraUnitsPerPixelUnits)
                .convertToUnits(LengthUnit.Millimeters).getValue();
        double scaledY = new Length(cameraUnitsPerPixelY, cameraUnitsPerPixelUnits)
                .convertToUnits(LengthUnit.Millimeters).getValue();
        double unitsPerPixelX = scaledUnitsForBoard(scaledX, currentUnitsPerPixel.getX(),
                boardUnitsPerPixel.getX());
        double unitsPerPixelY = scaledUnitsForBoard(scaledY, currentUnitsPerPixel.getY(),
                boardUnitsPerPixel.getY());
        if (!Double.isFinite(unitsPerPixelX) || !Double.isFinite(unitsPerPixelY)
                || unitsPerPixelX <= 0 || unitsPerPixelY <= 0) {
            return;
        }

        Rectangle2D visibleWorld = new Rectangle2D.Double(
                cameraLocation.getX() - viewPortWidth * unitsPerPixelX / 2,
                cameraLocation.getY() - viewPortHeight * unitsPerPixelY / 2,
                viewPortWidth * unitsPerPixelX, viewPortHeight * unitsPerPixelY);
        AffineTransform worldToView = worldToViewTransform(cameraLocation.getX(),
                cameraLocation.getY(), unitsPerPixelX, unitsPerPixelY,
                viewPortCenterX, viewPortCenterY);

        Graphics2D g2d = (Graphics2D) graphics.create();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            if (current.boardProfile.getBounds2D().intersects(visibleWorld)) {
                g2d.setColor(BOARD_COLOR);
                g2d.draw(worldToView.createTransformedShape(current.boardProfile));
            }
            drawBoardOrigin(g2d, current, worldToView, visibleWorld);

            int visibleCount = 0;
            for (ProjectedPlacement placement : current.placements) {
                if (placement.bounds.intersects(visibleWorld)) {
                    visibleCount++;
                }
            }
            for (ProjectedPlacement placement : current.placements) {
                if (!placement.bounds.intersects(visibleWorld)) {
                    continue;
                }
                drawPlacement(g2d, placement, worldToView, visibleCount);
            }
            drawLegend(g2d, current, visibleCount, viewPortCenterX, viewPortCenterY,
                    viewPortWidth, viewPortHeight);
        }
        finally {
            g2d.dispose();
        }
    }

    private void drawPlacement(Graphics2D g2d, ProjectedPlacement placement,
            AffineTransform worldToView, int visibleCount) {
        boolean placed = placement.type == Placement.Type.Placement
                && job.retrievePlacedStatus(boardLocation, placement.id);
        Color color = placement.missingFootprint ? MISSING_COLOR
                : !placement.enabled ? DISABLED_COLOR
                        : placement.type == Placement.Type.Fiducial ? FIDUCIAL_COLOR
                                : placed ? PLACED_COLOR : UNPLACED_COLOR;
        Shape body = worldToView.createTransformedShape(placement.body);
        Shape pads = worldToView.createTransformedShape(placement.pads);
        Composite originalComposite = g2d.getComposite();
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.16f));
        g2d.setColor(color);
        g2d.fill(body);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.34f));
        g2d.fill(pads);
        g2d.setComposite(originalComposite);
        g2d.setColor(color);
        g2d.draw(body);
        g2d.draw(pads);

        Point2D center = worldToView.transform(placement.center, null);
        Point2D direction = worldToView.transform(placement.direction, null);
        g2d.draw(new Line2D.Double(center, direction));
        g2d.draw(new Line2D.Double(center.getX() - 3, center.getY(),
                center.getX() + 3, center.getY()));
        g2d.draw(new Line2D.Double(center.getX(), center.getY() - 3,
                center.getX(), center.getY() + 3));

        Rectangle2D viewBounds = body.getBounds2D();
        if (visibleCount <= LABEL_VISIBLE_LIMIT
                || viewBounds.getWidth() >= 18 || viewBounds.getHeight() >= 18) {
            drawOutlinedText(g2d, placement.id, (float) center.getX() + 5,
                    (float) center.getY() - 5, color);
        }
    }

    private void drawBoardOrigin(Graphics2D g2d, Snapshot current,
            AffineTransform worldToView, Rectangle2D visibleWorld) {
        if (!visibleWorld.contains(current.origin)) {
            return;
        }
        Point2D origin = worldToView.transform(current.origin, null);
        Point2D xAxis = worldToView.transform(current.xAxis, null);
        Point2D yAxis = worldToView.transform(current.yAxis, null);
        g2d.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.setColor(new Color(0xFF5C5C));
        g2d.draw(new Line2D.Double(origin, xAxis));
        drawOutlinedText(g2d, "X", (float) xAxis.getX() + 3,
                (float) xAxis.getY(), new Color(0xFF5C5C));
        g2d.setColor(new Color(0x56DB7A));
        g2d.draw(new Line2D.Double(origin, yAxis));
        drawOutlinedText(g2d, "Y", (float) yAxis.getX() + 3,
                (float) yAxis.getY(), new Color(0x56DB7A));
        drawOutlinedText(g2d, "板原点", (float) origin.getX() + 5,
                (float) origin.getY() + 13, BOARD_COLOR);
        g2d.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
    }

    private void drawLegend(Graphics2D g2d, Snapshot current, int visibleCount,
            double viewPortCenterX, double viewPortCenterY,
            int viewPortWidth, int viewPortHeight) {
        String scaleMode = camera.isUnitsPerPixelAtZCalibrated()
                ? "Z高度校准" : "固定像素比例";
        String text = "整板投射  " + current.boardName + "  视野 " + visibleCount + "/"
                + current.placements.size() + "  板面Z "
                + String.format(Locale.ROOT, "%.3f mm", current.boardZ) + "  " + scaleMode;
        Font font = g2d.getFont().deriveFont(Font.BOLD, 11f);
        g2d.setFont(font);
        FontMetrics metrics = g2d.getFontMetrics();
        int availableWidth = Math.max(120, viewPortWidth - 16);
        if (metrics.stringWidth(text) + 14 > availableWidth) {
            text = "整板投射  视野 " + visibleCount + "/" + current.placements.size()
                    + "  Z " + String.format(Locale.ROOT, "%.3f mm", current.boardZ);
        }
        int x = (int) Math.round(viewPortCenterX - viewPortWidth / 2.0 + 8);
        int y = (int) Math.round(viewPortCenterY - viewPortHeight / 2.0 + 8);
        int width = Math.min(availableWidth, metrics.stringWidth(text) + 14);
        int height = metrics.getHeight() + 8;
        Composite original = g2d.getComposite();
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.72f));
        g2d.setColor(Color.BLACK);
        g2d.fillRoundRect(x, y, width, height, 8, 8);
        g2d.setComposite(original);
        g2d.setColor(BOARD_COLOR);
        g2d.drawString(text, x + 7, y + 5 + metrics.getAscent());
    }

    private static void drawOutlinedText(Graphics2D g2d, String text, float x, float y,
            Color color) {
        g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, 10f));
        g2d.setColor(new Color(0, 0, 0, 210));
        g2d.drawString(text, x - 1, y);
        g2d.drawString(text, x + 1, y);
        g2d.drawString(text, x, y - 1);
        g2d.drawString(text, x, y + 1);
        g2d.setColor(color);
        g2d.drawString(text, x, y);
    }

    private ProjectedPlacement createProjectedPlacement(Placement placement, Location location,
            boolean mirrorFootprint) {
        Shape body;
        Shape pads;
        boolean missing = false;
        Footprint footprint = placement.getPart() == null
                || placement.getPart().getPackage() == null ? null
                        : placement.getPart().getPackage().getFootprint();
        if (footprint == null) {
            missing = true;
            body = placement.getType() == Placement.Type.Fiducial
                    ? new Ellipse2D.Double(-1, -1, 2, 2)
                    : new Rectangle2D.Double(-1, -1, 2, 2);
            pads = new Rectangle2D.Double();
        }
        else {
            body = footprint.getBodyShape();
            pads = footprint.getPadsShape();
            if (body.getBounds2D().isEmpty() && pads.getBounds2D().isEmpty()) {
                missing = true;
                body = new Rectangle2D.Double(-1, -1, 2, 2);
            }
        }
        double unitScale = footprint == null ? 1.0
                : new Length(1, footprint.getUnits())
                        .convertToUnits(LengthUnit.Millimeters).getValue();
        AffineTransform placementTransform = placementTransform(location.getX(), location.getY(),
                location.getRotation(), unitScale, mirrorFootprint);
        Shape globalBody = placementTransform.createTransformedShape(body);
        Shape globalPads = placementTransform.createTransformedShape(pads);
        Rectangle2D bounds = globalBody.getBounds2D();
        bounds.add(globalPads.getBounds2D());
        Point2D center = new Point2D.Double(location.getX(), location.getY());
        Point2D direction = placementTransform.transform(
                new Point2D.Double(1.5 / unitScale, 0), null);
        return new ProjectedPlacement(placement.getId(), placement.getType(),
                placement.isEnabled(), missing, globalBody, globalPads, bounds, center, direction);
    }

    static AffineTransform placementTransform(double x, double y, double rotation,
            double unitScale, boolean mirror) {
        AffineTransform transform = new AffineTransform();
        transform.translate(x, y);
        transform.rotate(Math.toRadians(rotation));
        transform.scale(mirror ? -unitScale : unitScale, unitScale);
        return transform;
    }

    static AffineTransform worldToViewTransform(double cameraX, double cameraY,
            double unitsPerPixelX, double unitsPerPixelY,
            double centerX, double centerY) {
        AffineTransform transform = new AffineTransform();
        transform.translate(centerX, centerY);
        transform.scale(1.0 / unitsPerPixelX, -1.0 / unitsPerPixelY);
        transform.translate(-cameraX, -cameraY);
        return transform;
    }

    static double scaledUnitsForBoard(double scaledUnitsPerPixel,
            double currentUnitsPerPixel, double boardUnitsPerPixel) {
        if (currentUnitsPerPixel == 0) {
            return Double.NaN;
        }
        return Math.abs(boardUnitsPerPixel * scaledUnitsPerPixel / currentUnitsPerPixel);
    }

    static boolean isVisiblePlacement(Side placementSide, Side boardFacingSide) {
        return placementSide == boardFacingSide;
    }

    private static class Snapshot {
        final String boardName;
        final double boardZ;
        final Shape boardProfile;
        final Point2D origin;
        final Point2D xAxis;
        final Point2D yAxis;
        final List<ProjectedPlacement> placements;

        Snapshot(String boardName, double boardZ, Shape boardProfile, Point2D origin,
                Point2D xAxis, Point2D yAxis, List<ProjectedPlacement> placements) {
            this.boardName = boardName;
            this.boardZ = boardZ;
            this.boardProfile = boardProfile;
            this.origin = origin;
            this.xAxis = xAxis;
            this.yAxis = yAxis;
            this.placements = placements;
        }
    }

    private static class ProjectedPlacement {
        final String id;
        final Placement.Type type;
        final boolean enabled;
        final boolean missingFootprint;
        final Shape body;
        final Shape pads;
        final Rectangle2D bounds;
        final Point2D center;
        final Point2D direction;

        ProjectedPlacement(String id, Placement.Type type, boolean enabled,
                boolean missingFootprint, Shape body, Shape pads, Rectangle2D bounds,
                Point2D center, Point2D direction) {
            this.id = id;
            this.type = type;
            this.enabled = enabled;
            this.missingFootprint = missingFootprint;
            this.body = body;
            this.pads = pads;
            this.bounds = bounds;
            this.center = center;
            this.direction = direction;
        }
    }
}
