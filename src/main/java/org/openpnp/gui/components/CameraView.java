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

package org.openpnp.gui.components;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.prefs.Preferences;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import org.openpnp.CameraListener;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.components.reticle.Reticle;
import org.openpnp.gui.support.LengthConverter;
import org.openpnp.gui.support.TransientNotification;
import org.openpnp.machine.reference.camera.AbstractBroadcastingCamera;
import org.openpnp.machine.reference.safety.SafetyWorkbenchModel;
import org.openpnp.machine.reference.safety.SafetyWorkbenchStore;
import org.openpnp.machine.reference.safety.SafetyWorkbenchVisionScale;
import org.openpnp.machine.reference.safety.SafetyWorkbenchVisionScale.Resolution;
import org.openpnp.machine.reference.safety.SafetyWorkbenchVisionScale.Status;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Camera;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.UiUtils;
import org.openpnp.util.Utils2D;
import org.openpnp.util.VisionUtils;
import org.openpnp.util.XmlSerialize;
import org.pmw.tinylog.Logger;

@SuppressWarnings("serial")
public class CameraView extends JComponent implements CameraListener {
    private static final String PREF_RETICLE = "CamerView.reticle";
    private static final String DEFAULT_RETICLE_KEY = "DEFAULT_RETICLE_KEY";
    private static final String PREF_ZOOM_INCREMENT = "CamerView.zoomIncrement";
    private static final String PREF_RENDERING_QUALITY = "CamerView.renderingQuality";

    public enum ZoomSensitivity {
        High,
        Medium,
        Low
    };

    public static final EnumMap<ZoomSensitivity, Double> zoomIncrements = new EnumMap<>(Map.of(
            ZoomSensitivity.High, 2.0,
            ZoomSensitivity.Medium, Math.pow(2, 1.0 / 2),
            ZoomSensitivity.Low, Math.pow(2, 1.0 / 4)));

    private static final double DEFAULT_ZOOM_INCREMENT = zoomIncrements.get(ZoomSensitivity.Medium);

    private final static int HANDLE_DIAMETER = 8;

    private enum HandlePosition {
        NW,
        N,
        NE,
        E,
        SE,
        S,
        SW,
        W
    }

    private enum SelectionMode {
        Resizing,
        Moving,
        Creating
    }

    /**
     * The Camera we are viewing.
     */
    private Camera camera;

    /**
     * The last frame received, reported by the Camera.
     */
    private volatile BufferedImage lastFrame;

    private LinkedHashMap<Object, Reticle> reticles = new LinkedHashMap<>();

    private JPopupMenu popupMenu;

    /**
     * The last width and height of the component that we painted for. If the width or height is
     * different from these values at the start of paint we'll recalculate all the scaling data.
     */
    private double lastWidth, lastHeight;

    /**
     * The last width and height of the image that we painted for. If the width or height is
     * different from these values at the start of paint we'll recalculate all the scaling data.
     */
    private double lastSourceWidth, lastSourceHeight;

    private Location lastUnitsPerPixel;
    private Location lastBaseUnitsPerPixel;
    private Location lastVisionScaleCameraLocation;
    private long lastVisionScaleModelRevision = -1;
    private boolean lastVisionScaleEligible;
    private volatile SafetyWorkbenchModel visionScaleModel;
    private long lastVisionScaleNoticeAt;
    private volatile String dragJogInfo;
    private volatile long dragJogInfoUntil;

    /**
     * The width and height of the image after it has been scaled to fit the bounds of the
     * component.
     */
    private int scaledWidth, scaledHeight;

    /**
     * The ratio of scaled width and height to unscaled width and height. scaledWidth * scaleRatioX
     * = sourceWidth. scaleRatioX = sourceWidth / scaledWidth
     */
    private double scaleRatioX, scaleRatioY;

    /**
     * The Camera's units per pixel scaled at the same ratio as the image. That is, each pixel in
     * the scaled image is scaledUnitsPerPixelX wide and scaledUnitsPerPixelY high.
     */
    private double scaledUnitsPerPixelX, scaledUnitsPerPixelY;

    /**
     * The top left position within the component at which the scaled image can be drawn for it to
     * be centered.
     */
    private int imageX, imageY;

    private boolean selectionEnabled;
    /**
     * Rectangle describing the bounds of the selection in image coordinates.
     */
    private Rectangle selection;
    /**
     * The scaled version of the selection Rectangle. Rescaled any time the component's size is
     * changed.
     */
    private Rectangle selectionScaled;
    private SelectionMode selectionMode;
    private HandlePosition selectionActiveHandle;
    private int selectionStartX, selectionStartY;
    private float selectionFlashOpacity;
    private float selectionDashPhase;
    private static float[] selectionDashProfile = new float[] {6f, 6f};
    // 11 is the sum of the dash lengths minus 1.
    private static float selectionDashPhaseStart = 11f;

    private CameraViewSelectionTextDelegate selectionTextDelegate;

    private ScheduledExecutorService scheduledExecutor;

    private Preferences prefs = Preferences.userNodeForPackage(CameraView.class);

    private volatile String text;

    private boolean showImageInfo;

    private List<CameraViewActionListener> actionListeners = new ArrayList<>();

    private CameraViewFilter cameraViewFilter;

    private long flashStartTimeMs;
    private long flashLengthMs = 250;

    private boolean showName = false;
    
    private double zoom = 1d;
    private double zoomIncPerMouseWheelTick = DEFAULT_ZOOM_INCREMENT;
    
    private boolean dragJogging = false;
    private boolean lightToggling = false;

    
    private MouseEvent dragJoggingStart = null;
    private MouseEvent dragJoggingTarget = null;
    private final AtomicLong previewFrameSequence = new AtomicLong();
    private final CameraViewJogGate screenJogGate = new CameraViewJogGate();
    private static final long SCREEN_JOG_FRAME_TIMEOUT_MS = 1500;
    private static final double MAX_PRECISION_CORRECTION_MM = 2.0;
    private volatile PendingPrecisionJog pendingPrecisionJog;
    private volatile boolean precisionCorrectionBusy;
    private int dragJogHandleSize = 50;
    Color dragJogHandleInactiveColor = new Color(125, 125, 125);
    Color dragJogHandleActiveColor = Color.white;

    private static final class PendingPrecisionJog {
        private final long token;
        private final Camera camera;
        private final CameraViewPrecisionLocator.Target target;
        private final Location unitsPerPixel;
        private final double commandedDistanceMm;
        private final double viewZoom;

        private PendingPrecisionJog(long token, Camera camera,
                CameraViewPrecisionLocator.Target target, Location unitsPerPixel,
                double commandedDistanceMm, double viewZoom) {
            this.token = token;
            this.camera = camera;
            this.target = target;
            this.unitsPerPixel = unitsPerPixel;
            this.commandedDistanceMm = commandedDistanceMm;
            this.viewZoom = viewZoom;
        }
    }

    private int lightToggleSize = 40;
    Color lightToggleInactiveBgColor = new Color(255, 255, 255, 100);
    Color lightToggleActiveBgColor = new Color(0, 0, 0, 100);
    Color lightToggleInactiveColor = new Color(0, 0, 0, 200);
    Color lightToggleActiveColor = new Color(255, 255, 125, 200);

    /** Optional workflow controls rendered over the lower center of the live camera image. */
    public interface GuidedInspectionControlListener {
        void completeAndNext();

        void completeAndExit();
    }

    private enum GuidedInspectionControl {
        Next,
        Exit
    }

    private GuidedInspectionControlListener guidedInspectionControlListener;
    private GuidedInspectionControl guidedInspectionControlPressed;
    private GuidedInspectionControl guidedInspectionControlHover;
    private boolean guidedInspectionControlClickConsumed;

    long lastFrameReceivedTime = 0;
    MovingAverage fpsAverage = new MovingAverage(24);
    double fps = 0;
    public enum RenderingQuality {
        Low, High, BestScale
    }
    RenderingQuality renderingQuality = RenderingQuality.Low;

    public CameraView() {
        setBackground(Color.black);
        setOpaque(true);

        addMouseListener(mouseListener);
        addMouseMotionListener(mouseMotionListener);
        addComponentListener(componentListener);
        addMouseWheelListener(mouseWheelListener);

        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

        // TODO: Cancel this when it's not being used instead of spinning,
        // or maybe create a real thread and wait().
        scheduledExecutor.scheduleAtFixedRate(new Runnable() {
            public void run() {
                if (selectionEnabled && selection != null) {
                    // Adjust the dash phase so the line marches on the next
                    // paint
                    selectionDashPhase -= 1f;
                    if (selectionDashPhase < 0) {
                        selectionDashPhase = selectionDashPhaseStart;
                    }
                    repaint();
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
    }
    
    private String getReticlePrefKey() {
        return PREF_RETICLE + "." + camera.getId();
    }

    private String getZoomIncrementPrefKey() {
        return PREF_ZOOM_INCREMENT + "." + camera.getId();
    }

    private String getQualityRenderingPrefKey() {
        return PREF_RENDERING_QUALITY + "." + camera.getId();
    }

    public void addActionListener(CameraViewActionListener listener) {
        if (!actionListeners.contains(listener)) {
            actionListeners.add(listener);
        }
    }

    public boolean removeActionListener(CameraViewActionListener listener) {
        return actionListeners.remove(listener);
    }

    public void setCamera(Camera camera) {
        // turn off capture for the camera we are replacing, if any
        if (this.camera != null) {
            this.camera.stopContinuousCapture(this);
        }
        this.camera = camera;
        lastBaseUnitsPerPixel = null;
        lastVisionScaleCameraLocation = null;
        lastVisionScaleModelRevision = -1;
        lastVisionScaleEligible = false;
        // load the reticle pref, if any
        try {
            String reticleXml = prefs.get(getReticlePrefKey(), null);
            Reticle reticle = (Reticle) XmlSerialize.deserialize(reticleXml);
            setDefaultReticle(reticle);
        }
        catch (Exception e) {
            Logger.debug("Failed to load camera specific reticle, checking default.");
            try {
                String reticleXml = prefs.get(PREF_RETICLE, null);
                Reticle reticle = (Reticle) XmlSerialize.deserialize(reticleXml);
                setDefaultReticle(reticle);
            }
            catch (Exception e1) {
                Logger.debug("No reticle preference found.");
            }
        }

        // load the zoom increment pref, if any
        zoomIncPerMouseWheelTick = prefs.getDouble(getZoomIncrementPrefKey(), DEFAULT_ZOOM_INCREMENT);
        // reset invalid value from before v2.3
        if (!CameraView.zoomIncrements.containsValue(Double.valueOf(zoomIncPerMouseWheelTick))) {
            setZoomIncPerMouseWheelTick(DEFAULT_ZOOM_INCREMENT);
        }

        // load sub.pixel rendering prefs, if any.
        try {
            renderingQuality = RenderingQuality.valueOf(prefs.get(getQualityRenderingPrefKey(), RenderingQuality.Low.toString()));
        }
        catch (Exception e) {
            // ignore errors
        }
        // turn on capture for the new camera
        if (this.camera != null) {
            this.camera.startContinuousCapture(this);
        }
    }

    public Camera getCamera() {
        return camera;
    }

    public void setGuidedInspectionControlListener(GuidedInspectionControlListener listener) {
        guidedInspectionControlListener = listener;
        guidedInspectionControlPressed = null;
        guidedInspectionControlHover = null;
        repaint();
    }

    /** Releases the last displayed image and temporary filter when a secondary view is closed. */
    public synchronized void clearTransientImageState() {
        cameraViewFilter = null;
        lastFrame = null;
        text = null;
    }

    /**
     * Mirrors the complete visual presentation of another view of the same camera. This includes
     * the most recently presented (possibly pipeline-filtered) frame, dynamic recognition
     * reticles and overlay text. It deliberately does not copy interaction state such as drag
     * jogging or selections.
     *
     * @param source the authoritative view shown in the main OpenPnP window
     */
    public void mirrorPresentationFrom(CameraView source) {
        if (source == null || source == this || source.getCamera() != camera) {
            return;
        }
        BufferedImage frame;
        LinkedHashMap<Object, Reticle> sourceReticles;
        String sourceText;
        synchronized (source) {
            frame = source.lastFrame;
            sourceReticles = new LinkedHashMap<>(source.reticles);
            sourceText = source.text;
        }
        if (frame == null) {
            return;
        }
        synchronized (this) {
            BufferedImage previous = lastFrame;
            lastFrame = frame;
            reticles = sourceReticles;
            text = sourceText;
            if (previous == null || previous.getWidth() != frame.getWidth()
                    || previous.getHeight() != frame.getHeight()) {
                calculateScalingData();
            }
        }
        repaint();
    }

    public void setShowName(boolean showName) {
        this.showName = showName;
    }

    public boolean isShowName() {
        return this.showName;
    }

    public void setDefaultReticle(Reticle reticle) {
        setReticle(DEFAULT_RETICLE_KEY, reticle);

        prefs.put(getReticlePrefKey(), XmlSerialize.serialize(reticle));
        try {
            prefs.flush();
        }
        catch (Exception e) {

        }
    }

    public Reticle getDefaultReticle() {
        return reticles.get(DEFAULT_RETICLE_KEY);
    }

    /**
     * Temporarily hides the configured camera reticle without changing its persisted preference.
     * The returned reticle can be restored with {@link #restoreDefaultReticle(Reticle)}.
     */
    public Reticle suspendDefaultReticle() {
        return removeReticle(DEFAULT_RETICLE_KEY);
    }

    /** Restores a reticle hidden by {@link #suspendDefaultReticle()} without writing preferences. */
    public void restoreDefaultReticle(Reticle reticle) {
        setReticle(DEFAULT_RETICLE_KEY, reticle);
    }

    public void setReticle(Object key, Reticle reticle) {
        if (reticle == null) {
            removeReticle(key);
        }
        else {
            reticles.put(key, reticle);
        }
    }

    public Reticle getReticle(Object key) {
        return reticles.get(key);
    }

    public Reticle removeReticle(Object key) {
        return reticles.remove(key);
    }

    public CameraViewSelectionTextDelegate getSelectionTextDelegate() {
        return selectionTextDelegate;
    }

    public void setSelectionTextDelegate(CameraViewSelectionTextDelegate selectionTextDelegate) {
        this.selectionTextDelegate = selectionTextDelegate;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public double getZoomIncPerMouseWheelTick() {
        return zoomIncPerMouseWheelTick;
    }

    public void setZoomIncPerMouseWheelTick(double zoomIncPerMouseWheelTick) {
        prefs.putDouble(getZoomIncrementPrefKey(), zoomIncPerMouseWheelTick);
        this.zoomIncPerMouseWheelTick = zoomIncPerMouseWheelTick;
    }
    
    public RenderingQuality getRenderingQuality() {
        return renderingQuality;
    }

    public void setRenderingQuality(RenderingQuality renderingQuality) {
        prefs.put(getQualityRenderingPrefKey(), renderingQuality.toString());
        this.renderingQuality = renderingQuality;
        calculateScalingData();
    }

    /**
     * Checks to see if the viewing plane for this camera can be changed to different heights. This is
     * true if the camera's Units Per Pixel is different at two different heights.
     * 
     * @return true if the viewing plane can be changed
     */
    public boolean isViewingPlaneChangable() {
        Location upp1 = camera.getUnitsPerPixel(new Length(0.0, LengthUnit.Millimeters));
        Location upp2 = camera.getUnitsPerPixel(new Length(10.0, LengthUnit.Millimeters));
        return !upp1.equals(upp2);
    }

    /**
     * Causes a short flash in the CameraView to get the user's attention.
     */
    public void flash() {
        flashStartTimeMs = System.currentTimeMillis();
        scheduledExecutor.scheduleAtFixedRate(new Runnable() {
            public void run() {
                if (System.currentTimeMillis() - flashStartTimeMs < flashLengthMs) {
                    repaint();
                }
                else {
                    flashStartTimeMs = 0;
                    throw new RuntimeException();
                }
            }
        }, 0, 30, TimeUnit.MILLISECONDS);
    }

    public void setCameraViewFilter(CameraViewFilter cameraViewFilter) {
        this.cameraViewFilter = cameraViewFilter;
        if (camera.isAutoVisible()) {
            camera.ensureCameraVisible();
        }
    }

    public void showFilteredImage(final BufferedImage filteredImage, final long milliseconds) {
        showFilteredImage(filteredImage, null, milliseconds);
    }

    /**
     * Show an animation of N images instead of the camera image for milliseconds each frame. After 
     * N*milliseconds elapses the view goes back to showing the camera image. See also showFilteredImage().
     * 
     * @param images
     * @param texts
     * @param millseconds
     */
    public void showFilteredImages(BufferedImage [] filteredImages, String [] texts, long milliseconds) {
        setCameraViewFilter(new CameraViewFilter() {
            long t = System.currentTimeMillis();
            int n = filteredImages.length;

            @Override
            public BufferedImage filterCameraImage(Camera camera, BufferedImage image) {
                long elapsed = System.currentTimeMillis() - t;
                if (elapsed < milliseconds*n) {
                    int i = (int) (elapsed/milliseconds);
                    if (texts != null && i < texts.length) {
                        setText(texts[i]);
                    }
                    return filteredImages[i];
                }
                else {
                    if (texts != null) {
                        setText(null);
                    }
                    setCameraViewFilter(null);
                    return image;
                }
            }
        });
        // Make sure the filtered image is shown immediately and also counted as fps (for 0 or low fps cameras). 
        frameReceived(null);
    }

    /**
     * Show image instead of the camera image for milliseconds. After milliseconds elapses the view
     * goes back to showing the camera image. The image should be the same width and height as the
     * camera image otherwise the behavior is undefined. This function is intended to be used to
     * briefly show the result of image processing. This is a shortcut to
     * setCameraViewFilter(CameraViewFilter) which simply removes itself after the specified time.
     * 
     * In addition to showing the given image, if the text parameters is not null the text will be
     * shown during the timeout using setText().
     * 
     * @param image
     * @param text
     * @param millseconds
     */
    public void showFilteredImage(BufferedImage filteredImage, String text, long milliseconds) {
        showFilteredImages(new BufferedImage[] { filteredImage }, text == null ? null : new String [] { text }, milliseconds);
    }

    public BufferedImage captureSelectionImage() {
        if (selection == null || lastFrame == null) {
            return null;
        }

        selectionFlashOpacity = 1.0f;

        ScheduledFuture future = scheduledExecutor.scheduleAtFixedRate(new Runnable() {
            public void run() {
                if (selectionFlashOpacity > 0) {
                    selectionFlashOpacity -= 0.07;
                    selectionFlashOpacity = Math.max(0, selectionFlashOpacity);
                    repaint();
                }
                else {
                    throw new RuntimeException();
                }
            }
        }, 0, 30, TimeUnit.MILLISECONDS);

        int sx = selection.x;
        int sy = selection.y;
        int sw = selection.width;
        int sh = selection.height;

        BufferedImage image = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        Graphics g = image.getGraphics();
        g.drawImage(lastFrame, 0, 0, sw, sh, sx, sy, sx + sw, sy + sh, null);
        g.dispose();

        while (!future.isDone()) {
            
        }

        return image;
    }

    public Rectangle getSelection() {
        return selection;
    }

    @Override
    public void frameReceived(BufferedImage img) {
        if (cameraViewFilter != null) {
            img = cameraViewFilter.filterCameraImage(camera, img);
        }
        if (img == null) {
            return;
        }
        long frameSequence = previewFrameSequence.incrementAndGet();
        BufferedImage oldFrame = lastFrame;
        lastFrame = img;
        if (oldFrame == null || oldFrame.getWidth() != img.getWidth()
                || oldFrame.getHeight() != img.getHeight() || needsScaleRefresh()) {
            calculateScalingData();
        }
        fps = 1000.0 / fpsAverage.next(System.currentTimeMillis() - lastFrameReceivedTime);
        lastFrameReceivedTime = System.currentTimeMillis();
        repaint();
        if (screenJogGate.frameReceived(frameSequence)) {
            PendingPrecisionJog precisionJog = pendingPrecisionJog;
            pendingPrecisionJog = null;
            if (precisionJog != null && precisionJog.camera == camera) {
                precisionCorrectionBusy = true;
                BufferedImage settledFrame = img;
                scheduledExecutor.execute(() -> runPrecisionCorrection(
                        precisionJog, settledFrame));
            }
        }
    }

    /**
     * Calculates a bunch of scaling data that we cache to speed up painting. This is recalculated
     * when the size of the component or the size of the source changes. This method is
     * synchronized, along with paintComponent() so that the updates to the cached data are atomic.
     */
    private synchronized void calculateScalingData() {
        BufferedImage image = lastFrame;

        if (image == null) {
            return;
        }

        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;

        double destWidth = width, destHeight = height;

        lastWidth = width;
        lastHeight = height;

        lastSourceWidth = image.getWidth();
        lastSourceHeight = image.getHeight();
        
        double heightRatio = lastSourceHeight / destHeight;
        double widthRatio = lastSourceWidth / destWidth;

        if (heightRatio > widthRatio) {
            double aspectRatio = lastSourceWidth / lastSourceHeight;
            scaledHeight = (int) destHeight;
            scaledWidth = (int) (scaledHeight * aspectRatio);
        }
        else {
            double aspectRatio = lastSourceHeight / lastSourceWidth;
            scaledWidth = (int) destWidth;
            scaledHeight = (int) (scaledWidth * aspectRatio);
        }

        scaledWidth *= zoom;
        scaledHeight *= zoom;

        if (renderingQuality == RenderingQuality.BestScale) {
            // Bring to an integral scaling factor.
            double scalingFactor = lastSourceWidth > scaledWidth ? 
                    1./Math.max(1, Math.ceil(lastSourceWidth/scaledWidth))
                    : Math.max(1, Math.floor(scaledWidth/lastSourceWidth));
            scaledWidth = (int)(lastSourceWidth*scalingFactor);
            scaledHeight = (int)(lastSourceHeight*scalingFactor);
        }

        imageX = ins.left + (width / 2) - (scaledWidth / 2);
        imageY = ins.top + (height / 2) - (scaledHeight / 2);

        scaleRatioX = lastSourceWidth / (double) scaledWidth;
        scaleRatioY = lastSourceHeight / (double) scaledHeight;
        
        lastBaseUnitsPerPixel = camera.getUnitsPerPixelAtZ();
        SafetyWorkbenchModel workbench = getVisionScaleModel();
        Resolution centerScale = SafetyWorkbenchVisionScale.resolve(workbench, camera,
                camera.getLocation(), 0, 0);
        lastUnitsPerPixel = centerScale.isApplied()
                ? centerScale.getUnitsPerPixel() : lastBaseUnitsPerPixel;
        lastVisionScaleModelRevision = workbench.getRevision();
        lastVisionScaleEligible = isVisionScaleEligible(workbench);
        lastVisionScaleCameraLocation = camera.getLocation()
                .convertToUnits(LengthUnit.Millimeters);
        scaledUnitsPerPixelX = lastUnitsPerPixel.getX() * scaleRatioX;
        scaledUnitsPerPixelY = lastUnitsPerPixel.getY() * scaleRatioY;

        if (selectionEnabled && selection != null) {
            // setSelection() handles updating the scaled rectangle
            setSelection(selection);
        }
    }

    private SafetyWorkbenchModel getVisionScaleModel() {
        SafetyWorkbenchModel model = visionScaleModel;
        if (model == null) {
            model = SafetyWorkbenchStore.get();
            visionScaleModel = model;
        }
        return model;
    }

    private boolean isVisionScaleEligible(SafetyWorkbenchModel model) {
        return model.isVisionHeightCompensationEnabled() && model.isCertified()
                && camera.getLooking() == Camera.Looking.Down
                && camera.isUnitsPerPixelAtZCalibrated();
    }

    private synchronized boolean needsScaleRefresh() {
        Location baseUnitsPerPixel = camera.getUnitsPerPixelAtZ();
        if (lastBaseUnitsPerPixel == null || !baseUnitsPerPixel.equals(lastBaseUnitsPerPixel)) {
            return true;
        }
        SafetyWorkbenchModel model = getVisionScaleModel();
        boolean eligible = isVisionScaleEligible(model);
        if (eligible != lastVisionScaleEligible
                || model.getRevision() != lastVisionScaleModelRevision) {
            return true;
        }
        if (!eligible) {
            return false;
        }
        Location current = camera.getLocation().convertToUnits(LengthUnit.Millimeters);
        return lastVisionScaleCameraLocation == null
                || current.getLinearDistanceTo(lastVisionScaleCameraLocation) > 0.05;
    }

    @Override
    protected synchronized void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (lastFrame == null) {
            frameReceived(AbstractBroadcastingCamera.getCaptureErrorImage());
        }
        BufferedImage image = lastFrame;
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;
        Graphics2D g2d = (Graphics2D) g;
        g.setColor(getBackground());
        g2d.fillRect(ins.left, ins.top, width, height);
        if (image != null) {
            // Only render if there is a valid image.
            if (renderingQuality == RenderingQuality.Low) {
                g2d.drawImage(lastFrame, imageX, imageY, scaledWidth, scaledHeight, null);
            }
            else {
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                AffineTransform t = new AffineTransform();
                double scaleW = ((double)scaledWidth)/image.getWidth();
                double scaleH = ((double)scaledHeight)/image.getHeight();
                // Scaled
                t.translate(imageX, imageY);
                t.scale(scaleW, scaleH);
                g2d.drawImage(lastFrame, t, null);
            }

            double c = MainFrame.get().getMachineControls().getSelectedTool().getLocation()
                    .getRotation();

            for (Reticle reticle : reticles.values()) {
                reticle.draw(g2d, camera.getUnitsPerPixelAtZ().getUnits(), scaledUnitsPerPixelX,
                        scaledUnitsPerPixelY, ins.left + (width / 2), ins.top + (height / 2),
                        scaledWidth, scaledHeight, c);
            }

            if (text != null) {
                drawTextOverlay(g2d, 10, 10, text);
            }

            if (showName) {
                Dimension dim = measureTextOverlay(g2d, camera.getName());
                drawTextOverlay(g2d, 10, height - dim.height - 10, camera.getName());
            }

            String currentDragJogInfo = dragJogInfo;
            if (currentDragJogInfo != null
                    && System.currentTimeMillis() < dragJogInfoUntil) {
                int infoBottom = height - (showName ? 54 : 10);
                drawInfoLine(g2d, 10, infoBottom, currentDragJogInfo);
            }

            if (showImageInfo && text == null) {
                drawImageInfo(g2d, 10, 10, image);
            }

            if (selectionEnabled && selection != null) {
                paintSelection(g2d);
            }

            if (!selectionEnabled) {
                paintDragJogging(g2d);
                paintLightToggle(g2d);
                paintGuidedInspectionControls(g2d);
            }

            if (camera.isUnitsPerPixelAtZCalibrated()) {
                // Display the height of the reticle in the lower left corner if it is lower than
                // safe Z.
                if (camera.getLocation().getLengthZ().compareTo(camera.getSafeZ()) < 0) {   
                    LengthConverter lengthConverter = new LengthConverter();
                    String text = "Z: " + lengthConverter.convertForward(camera.getLocation().getLengthZ());
                    Dimension dim = measureTextOverlay(g2d, text);
                    drawTextOverlay(g2d, width - dim.width - 10, height - dim.height - 10, text);
                }
            }
        }
        else {
            g.setColor(Color.red);
            g.drawLine(ins.left, ins.top, ins.right, ins.bottom);
            g.drawLine(ins.right, ins.top, ins.left, ins.bottom);
        }

        if (flashStartTimeMs > 0) {
            long timeLeft = flashLengthMs - (System.currentTimeMillis() - flashStartTimeMs);
            float alpha = (1f / flashLengthMs) * timeLeft;
            alpha = Math.min(alpha, 1);
            alpha = Math.max(alpha, 0);
            g2d.setColor(new Color(1f, 1f, 1f, alpha));
            g2d.fillRect(0, 0, getWidth(), getHeight());
        }
    }
    
    private boolean isPointInsideRectangle(int pointX, int pointY, int rectX1, int rectY1, int rectX2, int rectY2) {
        return pointX >= rectX1 && pointX <= rectX2 && pointY >= rectY1 && pointY <= rectY2;
    }
    
    private boolean isPointInsideCircle(int pointX, int pointY, int circleX, int circleY, int circleRadius) {
        return Math.pow(pointX - circleX, 2) + Math.pow(pointY - circleY, 2) <= Math.pow(circleRadius, 2);
    }
    
    private boolean isPointInsideDragJogRotationHandle(int x, int y) {
        HeadMountable selectedTool = MainFrame.get().getMachineControls().getSelectedTool(); 
        if (selectedTool.getAxisRotation() == null) {
            return false;
        }
        
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;
        int handleSize = 50;
        int halfHandleSize = handleSize / 2;
        
        // The rotation handle is drawn on an imaginary circle centered in the view
        double rotHandleRadius = Math.min(width, height) / 2 * .80;
        double rotHandleAngle = -Utils2D.normalizeAngle(selectedTool.getLocation().getRotation() + 90);
        double rotHandleX = rotHandleRadius * Math.cos(Math.toRadians(rotHandleAngle)) + (width / 2.);
        double rotHandleY = rotHandleRadius * Math.sin(Math.toRadians(rotHandleAngle)) + (height / 2.);
        
        return isPointInsideCircle(x, y, (int) rotHandleX, (int) rotHandleY, (int) halfHandleSize);
    }
    
    private boolean isPointInsideDragJogXyHandle(int x, int y) {
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;
        int halfHandleSize = dragJogHandleSize / 2;
        
        int xyHandleX0 = width / 2 - halfHandleSize;
        int xyHandleY0 = height / 2 - halfHandleSize;
        int xyHandleX1 = xyHandleX0 + dragJogHandleSize;
        int xyHandleY1 = xyHandleY0 + dragJogHandleSize;
        
        return isPointInsideRectangle(x, y, xyHandleX0, xyHandleY0, xyHandleX1, xyHandleY1);
    }
    
    private boolean isPointInsideDragJogHandle(int x, int y) {
        return isPointInsideDragJogXyHandle(x, y) || isPointInsideDragJogRotationHandle(x, y);
    }

    private boolean isPointInsideLightToggle(int x, int y) {
        Actuator actuator = camera.getLightActuator();
        if (actuator == null) {
            return false;
        }
        
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;
        // Center
        int x0 = width - lightToggleSize/2;
        int y0 = lightToggleSize/2;
        
        double distance = Math.sqrt(Math.pow(x-x0, 2)+ Math.pow(y-y0, 2));

        return distance <= lightToggleSize/2;
    }

    private void drawCircle(Graphics2D g2d, int centerX, int centerY, int radius) {
        g2d.drawArc(centerX - radius, centerY - radius, radius * 2, radius * 2, 0, 360);
    }

    private double snapRotationAngleToTypicalAngles(double angle) {
        // Round to typical pcb placing angles
        return ((int) Math.round(angle / 45)) * 45.0;
    }
    
    private void paintDragJogRotationHandle(Graphics2D g2d, boolean active) {
        HeadMountable selectedTool = MainFrame.get().getMachineControls().getSelectedTool(); 
        if (selectedTool.getAxisRotation() == null) {
            return;
        }
        
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;

        // The rotation handle is drawn on an imaginary circle centered in the view
        double rotHandleRadius = Math.min(width, height) / 2 * .80;
        double rotHandleAngle = -Utils2D.normalizeAngle(selectedTool.getLocation().getRotation() + 90);
        double rotHandleX = rotHandleRadius * Math.cos(Math.toRadians(rotHandleAngle)) + (width / 2.);
        double rotHandleY = rotHandleRadius * Math.sin(Math.toRadians(rotHandleAngle)) + (height / 2.);

        // Draw the circular handle at it's original position in the inactive color
        g2d.setColor(dragJogHandleInactiveColor);
        drawCircle(g2d, (int) rotHandleX, (int) rotHandleY, dragJogHandleSize / 2);

        if (active) {
            // Draw the imaginary circle as a guideline for the rotation handle
            g2d.setColor(dragJogHandleInactiveColor);
            drawCircle(g2d, width / 2, height / 2, (int) rotHandleRadius);
            
            int targetX = dragJoggingTarget.getX();
            int targetY = dragJoggingTarget.getY();
            
            // Now draw the circular handle at it's target position in the active color
            double rotTargetHandleAngle = Math.toDegrees(Math.atan2(targetY - (height / 2), targetX - (width / 2)));
            rotTargetHandleAngle = Utils2D.normalizeAngle(rotTargetHandleAngle);

            // If the alt button is pressed snap to certain angles
            if(dragJoggingTarget.isAltDown()) {
                rotTargetHandleAngle = snapRotationAngleToTypicalAngles(rotTargetHandleAngle);
            }

            double rotTargetHandleX = rotHandleRadius * Math.cos(Math.toRadians(rotTargetHandleAngle)) + (width / 2.);
            double rotTargetHandleY = rotHandleRadius * Math.sin(Math.toRadians(rotTargetHandleAngle)) + (height / 2.);
            g2d.setColor(dragJogHandleActiveColor);
            drawCircle(g2d, (int) rotTargetHandleX, (int) rotTargetHandleY, dragJogHandleSize / 2);
            
            // And draw a rotated crosshair to help the user line things up
            double x1 = rotHandleRadius * Math.cos(Math.toRadians(rotTargetHandleAngle)) + (width / 2.);
            double y1 = rotHandleRadius * Math.sin(Math.toRadians(rotTargetHandleAngle)) + (height / 2.);
            double x2 = rotHandleRadius * Math.cos(Math.toRadians(rotTargetHandleAngle + 180)) + (width / 2.);
            double y2 = rotHandleRadius * Math.sin(Math.toRadians(rotTargetHandleAngle + 180)) + (height / 2.);
            g2d.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
            x1 = rotHandleRadius * Math.cos(Math.toRadians(rotTargetHandleAngle + 90)) + (width / 2.);
            y1 = rotHandleRadius * Math.sin(Math.toRadians(rotTargetHandleAngle + 90)) + (height / 2.);
            x2 = rotHandleRadius * Math.cos(Math.toRadians(rotTargetHandleAngle + 270)) + (width / 2.);
            y2 = rotHandleRadius * Math.sin(Math.toRadians(rotTargetHandleAngle + 270)) + (height / 2.);
            g2d.drawLine((int) x1, (int) y1, (int) x2, (int) y2);
        }
    }
    
    private void paintDragJogXyHandle(Graphics2D g2d, boolean active) {
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;

        int xyHandleX0 = width / 2 - dragJogHandleSize / 2;
        int xyHandleY0 = height / 2 - dragJogHandleSize / 2;

        g2d.setColor(dragJogHandleInactiveColor);
        g2d.drawRect(xyHandleX0, xyHandleY0, dragJogHandleSize, dragJogHandleSize);

        if (active) {
            int targetX = dragJoggingTarget.getX();
            int targetY = dragJoggingTarget.getY();
            
            int xyTargetHandleX0 = targetX - dragJogHandleSize / 2;
            int xyTargetHandleY0 = targetY - dragJogHandleSize / 2;
            g2d.setColor(dragJogHandleActiveColor);
            g2d.drawRect(xyTargetHandleX0, xyTargetHandleY0, dragJogHandleSize, dragJogHandleSize);
            
            g2d.drawLine(xyTargetHandleX0 + dragJogHandleSize / 2, xyTargetHandleY0,
                    xyTargetHandleX0 + dragJogHandleSize / 2, xyTargetHandleY0 + dragJogHandleSize);
            g2d.drawLine(xyTargetHandleX0, xyTargetHandleY0 + dragJogHandleSize / 2,
                    xyTargetHandleX0 + dragJogHandleSize, xyTargetHandleY0 + dragJogHandleSize / 2);
            
            g2d.drawLine(xyHandleX0 + dragJogHandleSize / 2, 
                    xyHandleY0 + dragJogHandleSize / 2,
                    targetX, targetY);
        }
    }
    
    private void paintDragJogging(Graphics2D g2d) {
        paintDragJogXyHandle(g2d, isDragJogging() 
                && !isPointInsideDragJogRotationHandle(dragJoggingStart.getX(), dragJoggingStart.getY()));
        paintDragJogRotationHandle(g2d, isDragJogging() 
                && isPointInsideDragJogRotationHandle(dragJoggingStart.getX(), dragJoggingStart.getY()));
    }

    private void paintLightToggle(Graphics2D g2d) {
        Actuator actuator = camera.getLightActuator();
        if (actuator == null) {
            return;
        }
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;
        // Center
        int x0 = width - lightToggleSize/2;
        int y0 = lightToggleSize/2;
        if (isTogglingLight()) {
            x0++;
            y0++;
        }
        boolean active = actuator.isActuated() != null && actuator.isActuated();
        for (int pass = 0 ; pass < 2; pass++) {
            if (pass == 0) {
                g2d.setStroke(new BasicStroke(active ? 4 : 4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2d.setColor(active ? lightToggleActiveBgColor : lightToggleInactiveBgColor);
            }
            else {
                g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2d.setColor(active ? lightToggleActiveColor : lightToggleInactiveColor);
            }
            drawCircle(g2d, x0, y0, (int)(lightToggleSize*0.27));
            double r0 = lightToggleSize*0.36;
            double r1 = lightToggleSize*0.5-1;
            for (int angle = 15; angle < 360; angle += 30) {
                g2d.drawLine(
                        (int)(x0 + 0.5 + r0*Math.cos(Math.toRadians(angle))), 
                        (int)(y0 + 0.5 + r0*Math.sin(Math.toRadians(angle))),
                        (int)(x0 + 0.5 + r1*Math.cos(Math.toRadians(angle))), 
                        (int)(y0 + 0.5 + r1*Math.sin(Math.toRadians(angle))));
            }
        }
    }

    private Rectangle guidedInspectionControlBounds(GuidedInspectionControl control) {
        int size = Math.max(38, Math.min(48, Math.min(getWidth(), getHeight()) / 7));
        int gap = 16;
        int centerY = getHeight() - size / 2 - 12;
        int centerX = getWidth() / 2
                + (control == GuidedInspectionControl.Next ? -(size + gap) / 2
                        : (size + gap) / 2);
        return new Rectangle(centerX - size / 2, centerY - size / 2, size, size);
    }

    private GuidedInspectionControl guidedInspectionControlAt(int x, int y) {
        if (guidedInspectionControlListener == null) {
            return null;
        }
        for (GuidedInspectionControl control : GuidedInspectionControl.values()) {
            if (guidedInspectionControlBounds(control).contains(x, y)) {
                return control;
            }
        }
        return null;
    }

    private void paintGuidedInspectionControls(Graphics2D graphics) {
        if (guidedInspectionControlListener == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            for (GuidedInspectionControl control : GuidedInspectionControl.values()) {
                Rectangle bounds = guidedInspectionControlBounds(control);
                boolean active = control == guidedInspectionControlPressed
                        || control == guidedInspectionControlHover;
                int offset = control == guidedInspectionControlPressed ? 1 : 0;
                int cx = bounds.x + bounds.width / 2 + offset;
                int cy = bounds.y + bounds.height / 2 + offset;
                int radius = bounds.width / 2 - 2;

                // Two-pass hollow artwork remains readable over both bright and dark camera frames.
                g2.setColor(new Color(0, 0, 0, 150));
                g2.setStroke(new BasicStroke(active ? 5f : 4f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                drawGuidedInspectionGlyph(g2, control, cx, cy, radius);
                g2.setColor(active ? new Color(88, 220, 245, 235)
                        : new Color(238, 248, 250, 220));
                g2.setStroke(new BasicStroke(active ? 2.6f : 2f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                drawGuidedInspectionGlyph(g2, control, cx, cy, radius);
                String label = control == GuidedInspectionControl.Next ? "保存并下一项" : "保存并退出";
                g2.setFont(g2.getFont().deriveFont(java.awt.Font.BOLD, 11f));
                int labelX = cx - g2.getFontMetrics().stringWidth(label) / 2;
                int labelY = bounds.y - 5;
                g2.setColor(new Color(0, 0, 0, 190));
                g2.drawString(label, labelX + 1, labelY + 1);
                g2.setColor(active ? new Color(88, 220, 245, 245)
                        : new Color(245, 250, 252, 235));
                g2.drawString(label, labelX, labelY);
            }
        }
        finally {
            g2.dispose();
        }
    }

    private void drawGuidedInspectionGlyph(Graphics2D g2, GuidedInspectionControl control,
            int cx, int cy, int radius) {
        drawCircle(g2, cx, cy, radius);
        int left = cx - radius / 2;
        int mid = cy + 1;
        g2.drawLine(left, mid, cx - radius / 7, cy + radius / 3);
        g2.drawLine(cx - radius / 7, cy + radius / 3, cx + radius / 4,
                cy - radius / 3);
        if (control == GuidedInspectionControl.Next) {
            int x = cx + radius / 3;
            g2.drawLine(x, cy - radius / 4, x + radius / 3, cy);
            g2.drawLine(x + radius / 3, cy, x, cy + radius / 4);
        }
        else {
            int size = Math.max(5, radius / 3);
            g2.drawRect(cx + radius / 4, cy - size / 2, size, size);
        }
    }

    private void paintSelection(Graphics2D g2d) {
        int rx = selectionScaled.x;
        int ry = selectionScaled.y;
        int rw = selectionScaled.width;
        int rh = selectionScaled.height;
        int rx2 = rx + rw;
        int ry2 = ry + rh;
        int rxc = rx + rw / 2;
        int ryc = ry + rh / 2;

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Draw the dashed rectangle, black background, white dashes
        g2d.setColor(Color.black);
        g2d.setStroke(new BasicStroke(1f));
        g2d.drawRect(rx, ry, rw, rh);
        g2d.setColor(Color.white);
        g2d.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0,
                selectionDashProfile, selectionDashPhase));
        g2d.drawRect(rx, ry, rw, rh);

        if (selectionMode != SelectionMode.Creating) {
            // If we're not drawing a whole new rectangle, draw the
            // handles for the existing one.
            drawHandle(g2d, rx, ry);
            drawHandle(g2d, rx2, ry);
            drawHandle(g2d, rx, ry2);
            drawHandle(g2d, rx2, ry2);

            drawHandle(g2d, rxc, ry);
            drawHandle(g2d, rx2, ryc);
            drawHandle(g2d, rxc, ry2);
            drawHandle(g2d, rx, ryc);
        }

        if (selectionTextDelegate != null) {
            String text = selectionTextDelegate.getSelectionText(this);
            if (text != null) {
                drawTextOverlay(g2d, (int) (rx + rw + 6), (int) (ry + rh + 6), text);
            }
        }

        if (selectionFlashOpacity > 0) {
            g2d.setColor(new Color(1.0f, 1.0f, 1.0f, selectionFlashOpacity));
            g2d.fillRect(rx, ry, rw, rh);
        }
    }

    /**
     * Draws a standard handle centered on the given x and y position.
     * 
     * @param g2d
     * @param x
     * @param y
     */
    private static void drawHandle(Graphics2D g2d, int x, int y) {
        g2d.setStroke(new BasicStroke(1f));
        g2d.setColor(new Color(153, 153, 187));
        g2d.fillArc(x - HANDLE_DIAMETER / 2, y - HANDLE_DIAMETER / 2, HANDLE_DIAMETER,
                HANDLE_DIAMETER, 0, 360);
        g2d.setColor(Color.white);
        g2d.drawArc(x - HANDLE_DIAMETER / 2, y - HANDLE_DIAMETER / 2, HANDLE_DIAMETER,
                HANDLE_DIAMETER, 0, 360);
    }

    /**
     * Gets the HandlePosition, if any, at the given x and y. Returns null if there is not a handle
     * at that position.
     * 
     * @param x
     * @param y
     * @return
     */
    private HandlePosition getSelectionHandleAtPosition(int x, int y) {
        if (selection == null) {
            return null;
        }

        int rx = selectionScaled.x;
        int ry = selectionScaled.y;
        int rw = selectionScaled.width;
        int rh = selectionScaled.height;
        int rx2 = rx + rw;
        int ry2 = ry + rh;
        int rxc = rx + rw / 2;
        int ryc = ry + rh / 2;

        if (isWithinHandle(x, y, rx, ry)) {
            return HandlePosition.NW;
        }
        else if (isWithinHandle(x, y, rx2, ry)) {
            return HandlePosition.NE;
        }
        else if (isWithinHandle(x, y, rx, ry2)) {
            return HandlePosition.SW;
        }
        else if (isWithinHandle(x, y, rx2, ry2)) {
            return HandlePosition.SE;
        }
        else if (isWithinHandle(x, y, rxc, ry)) {
            return HandlePosition.N;
        }
        else if (isWithinHandle(x, y, rx2, ryc)) {
            return HandlePosition.E;
        }
        else if (isWithinHandle(x, y, rxc, ry2)) {
            return HandlePosition.S;
        }
        else if (isWithinHandle(x, y, rx, ryc)) {
            return HandlePosition.W;
        }
        return null;
    }

    /**
     * A specialization of isWithin() that uses uses the bounding box of a handle.
     * 
     * @param x
     * @param y
     * @param handleX
     * @param handleY
     * @return
     */
    private static boolean isWithinHandle(int x, int y, int handleX, int handleY) {
        return isWithin(x, y, handleX - 4, handleY - 4, 8, 8);
    }

    private static boolean isWithin(int pointX, int pointY, int boundsX, int boundsY,
            int boundsWidth, int boundsHeight) {
        return pointX >= boundsX && pointX <= (boundsX + boundsWidth) && pointY >= boundsY
                && pointY <= (boundsY + boundsHeight);
    }

    private static Rectangle normalizeRectangle(Rectangle r) {
        return normalizeRectangle(r.x, r.y, r.width, r.height);
    }

    /**
     * Builds a rectangle with the given parameters. If the width or height is negative the
     * corresponding x or y value is modified and the width or height is made positive.
     * 
     * @param x
     * @param y
     * @param width
     * @param height
     * @return
     */
    private static Rectangle normalizeRectangle(int x, int y, int width, int height) {
        if (width < 0) {
            width *= -1;
            x -= width;
        }
        if (height < 0) {
            height *= -1;
            y -= height;
        }
        return new Rectangle(x, y, width, height);
    }

    /**
     * Draws text in a nice bubble at the given position. Newline characters in the text cause line
     * breaks.
     * 
     * @param g2d
     * @param topLeftX
     * @param topLeftY
     * @param text
     */
    private static void drawTextOverlay(Graphics2D g2d, int topLeftX, int topLeftY, String text) {
        Insets insets = new Insets(10, 10, 10, 10);
        int interLineSpacing = 4;
        int cornerRadius = 8;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.setFont(g2d.getFont().deriveFont(12.0f));
        String[] lines = text.split("\n");
        List<TextLayout> textLayouts = new ArrayList<>();
        int textWidth = 0, textHeight = 0;
        for (String line : lines) {
            TextLayout textLayout = new TextLayout(line, g2d.getFont(), g2d.getFontRenderContext());
            textWidth = (int) Math.max(textWidth, textLayout.getBounds().getWidth());
            textHeight += (int) textLayout.getBounds().getHeight() + interLineSpacing;
            textLayouts.add(textLayout);
        }
        textHeight -= interLineSpacing;
        g2d.setColor(new Color(0, 0, 0, 0.75f));
        g2d.fillRoundRect(topLeftX, topLeftY, textWidth + insets.left + insets.right,
                textHeight + insets.top + insets.bottom, cornerRadius, cornerRadius);
        g2d.setColor(Color.white);
        g2d.drawRoundRect(topLeftX, topLeftY, textWidth + insets.left + insets.right,
                textHeight + insets.top + insets.bottom, cornerRadius, cornerRadius);
        int yPen = topLeftY + insets.top;
        for (TextLayout textLayout : textLayouts) {
            yPen += textLayout.getBounds().getHeight();
            textLayout.draw(g2d, topLeftX + insets.left, yPen);
            yPen += interLineSpacing;
        }
    }

    private static Dimension measureTextOverlay(Graphics2D g2d, String text) {
        Insets insets = new Insets(10, 10, 10, 10);
        int interLineSpacing = 4;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.setFont(g2d.getFont().deriveFont(12.0f));
        String[] lines = text.split("\n");
        List<TextLayout> textLayouts = new ArrayList<>();
        int textWidth = 0, textHeight = 0;
        for (String line : lines) {
            TextLayout textLayout = new TextLayout(line, g2d.getFont(), g2d.getFontRenderContext());
            textWidth = (int) Math.max(textWidth, textLayout.getBounds().getWidth());
            textHeight += (int) textLayout.getBounds().getHeight() + interLineSpacing;
            textLayouts.add(textLayout);
        }
        textHeight -= interLineSpacing;
        return new Dimension(textWidth + insets.left + insets.right,
                textHeight + insets.top + insets.bottom);
    }

    private static void drawInfoLine(Graphics2D graphics, int left, int bottom, String text) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(g.getFont().deriveFont(11f));
        int width = g.getFontMetrics().stringWidth(text);
        int height = g.getFontMetrics().getHeight() + 6;
        g.setColor(new Color(12, 17, 20, 145));
        g.fillRoundRect(left, bottom - height, width + 12, height, 6, 6);
        g.setColor(new Color(184, 199, 207, 220));
        g.drawString(text, left + 6, bottom - 5);
        g.dispose();
    }

    private void drawImageInfo(Graphics2D g2d, int topLeftX, int topLeftY,
            BufferedImage image) {
        if (image == null) {
            return;
        }
        String text = String.format("Resolution: %d x %d\nZoom: %d%%\nFPS: %.1f\nHistogram:", 
                image.getWidth(),
                image.getHeight(), 
                (int) (zoom * 100),
                fps);
        Insets insets = new Insets(10, 10, 10, 10);
        int interLineSpacing = 4;
        int cornerRadius = 8;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.setFont(g2d.getFont().deriveFont(12.0f));
        String[] lines = text.split("\n");
        List<TextLayout> textLayouts = new ArrayList<>();
        int textWidth = 0, textHeight = 0;
        for (String line : lines) {
            TextLayout textLayout = new TextLayout(line, g2d.getFont(), g2d.getFontRenderContext());
            textWidth = (int) Math.max(textWidth, textLayout.getBounds().getWidth());
            textHeight += (int) textLayout.getBounds().getHeight() + interLineSpacing;
            textLayouts.add(textLayout);
        }
        textHeight -= interLineSpacing;

        int histogramHeight = 50 + 2;
        int histogramWidth = 255 + 2;

        int width = Math.max(textWidth, histogramWidth);
        int height = textHeight + histogramHeight;

        g2d.setColor(new Color(0, 0, 0, 0.75f));
        g2d.fillRoundRect(topLeftX, topLeftY, width + insets.left + insets.right,
                height + insets.top + insets.bottom, cornerRadius, cornerRadius);
        g2d.setColor(Color.white);
        g2d.drawRoundRect(topLeftX, topLeftY, width + insets.left + insets.right,
                height + insets.top + insets.bottom, cornerRadius, cornerRadius);
        int yPen = topLeftY + insets.top;
        for (TextLayout textLayout : textLayouts) {
            yPen += textLayout.getBounds().getHeight();
            textLayout.draw(g2d, topLeftX + insets.left, yPen);
            yPen += interLineSpacing;
        }

        g2d.setColor(new Color(1, 1, 1, 0.20f));
        g2d.fillRect(topLeftX + insets.left, yPen, histogramWidth, histogramHeight);

        // Calculate the histogram
        long[][] histogram = VisionUtils.computeImageHistogram(image);
        // find the highest value in the histogram
        long maxVal = 0;
        for (int channel = 0; channel < 3; channel++) {
            // Smooth it 
            long lastBucket = histogram[channel][0];
            for (int bucket = 1; bucket < 255; bucket++) {
                long currentBucket = histogram[channel][bucket];
                histogram[channel][bucket] = 
                        (currentBucket*2
                                + lastBucket
                                + histogram[channel][bucket+1])/4;
                lastBucket = currentBucket;
            }
            long [] sorted = histogram[channel].clone();
            Arrays.sort(sorted);
            // Exclude the largest two extremes, typically saturation values. 
            maxVal = Math.max(maxVal, sorted[sorted.length-1-2]);
        }
        // and scale it to 50 pixels tall
        double scale = 50.0 / maxVal;
        Color[] colors = new Color[] {new Color(255, 0, 0), new Color(0, 255, 0), new Color(0, 0, 255)};
        for (int bucket = 0; bucket < 256; bucket++) {
            // Rank the three colors, we want to draw overlapping histogram bars with mixed colors.
            TreeMap<Double, Integer> map = new TreeMap<>();
            map.put(histogram[0][bucket]+0.1, 0);
            map.put(histogram[1][bucket]+0.2, 1);
            map.put(histogram[2][bucket]+0.3, 2);
            // Start with white, where all three colors overlap.
            Color color = new Color(255, 255, 255);
            int value0 = 0;
            for (Entry<Double, Integer> entry : map.entrySet()) {
                g2d.setColor(color);
                int value1 = (int) Math.min(50, (entry.getKey() * scale) + 0.5);
                if (value1 > value0) {
                    g2d.drawLine(topLeftX + insets.left + 1 + bucket, yPen + 1 + 50 - value0 - 1,
                        topLeftX + insets.left + 1 + bucket, yPen + 1 + 50 - value1);
                }
                value0 = value1;
                // Subtract the color that is out.
                color = new Color(
                        color.getRed() - colors[entry.getValue()].getRed(), 
                        color.getGreen() - colors[entry.getValue()].getGreen(),
                        color.getBlue() - colors[entry.getValue()].getBlue());
            }
        }
    }

    /**
     * Changes the HandlePosition to it's inverse if the given rectangle has a negative width,
     * height or both.
     * 
     * @param r
     */
    private static HandlePosition getOpposingHandle(Rectangle r, HandlePosition handlePosition) {
        if (r.getWidth() < 0 && r.getHeight() < 0) {
            if (handlePosition == HandlePosition.NW) {
                return HandlePosition.SE;
            }
            else if (handlePosition == HandlePosition.NE) {
                return HandlePosition.SW;
            }
            else if (handlePosition == HandlePosition.SE) {
                return HandlePosition.NW;
            }
            else if (handlePosition == HandlePosition.SW) {
                return HandlePosition.NE;
            }
        }
        else if (r.getWidth() < 0) {
            if (handlePosition == HandlePosition.NW) {
                return HandlePosition.NE;
            }
            else if (handlePosition == HandlePosition.NE) {
                return HandlePosition.NW;
            }
            else if (handlePosition == HandlePosition.SE) {
                return HandlePosition.SW;
            }
            else if (handlePosition == HandlePosition.SW) {
                return HandlePosition.SE;
            }
            else if (handlePosition == HandlePosition.E) {
                return HandlePosition.W;
            }
            else if (handlePosition == HandlePosition.W) {
                return HandlePosition.E;
            }
        }
        else if (r.getHeight() < 0) {
            if (handlePosition == HandlePosition.SW) {
                return HandlePosition.NW;
            }
            else if (handlePosition == HandlePosition.SE) {
                return HandlePosition.NE;
            }
            else if (handlePosition == HandlePosition.NW) {
                return HandlePosition.SW;
            }
            else if (handlePosition == HandlePosition.NE) {
                return HandlePosition.SE;
            }
            else if (handlePosition == HandlePosition.S) {
                return HandlePosition.N;
            }
            else if (handlePosition == HandlePosition.N) {
                return HandlePosition.S;
            }
        }
        return handlePosition;
    }

    /**
     * Set the selection rectangle in image coordinates.
     * 
     * @param x
     * @param y
     * @param width
     * @param height
     */
    public void setSelection(int x, int y, int width, int height) {
        setSelection(new Rectangle(x, y, width, height));
    }

    /**
     * Set the selection rectangle in image coordinates.
     * 
     * @param r
     */
    public void setSelection(Rectangle r) {
        if (r == null) {
            selection = null;
            selectionMode = null;
        }
        else {
            selectionActiveHandle = getOpposingHandle(r, selectionActiveHandle);
            selection = normalizeRectangle(r);

            int rx = (int) (imageX + selection.x / scaleRatioX);
            int ry = (int) (imageY + selection.y / scaleRatioY);
            int rw = (int) (selection.width / scaleRatioX);
            int rh = (int) (selection.height / scaleRatioY);
            selectionScaled = new Rectangle(rx, ry, rw, rh);
        }
    }

    /**
     * Set the selection rectangle in component coordinates. Updates the selection property with the
     * properly scaled coordinates.
     * 
     * @param x
     * @param y
     * @param width
     * @param height
     */
    private void setScaledSelection(int x, int y, int width, int height) {
        selectionScaled = new Rectangle(x, y, width, height);
        selectionActiveHandle = getOpposingHandle(selectionScaled, selectionActiveHandle);
        selectionScaled = normalizeRectangle(selectionScaled);

        int rx = (int) ((x - imageX) * scaleRatioX);
        int ry = (int) ((y - imageY) * scaleRatioY);
        int rw = (int) (width * scaleRatioX);
        int rh = (int) (height * scaleRatioY);

        selection = new Rectangle(rx, ry, rw, rh);
    }

    public boolean isSelectionEnabled() {
        return selectionEnabled;
    }

    public void setSelectionEnabled(boolean selectionEnabled) {
        this.selectionEnabled = selectionEnabled;
    }

    public boolean isShowImageInfo() {
        return showImageInfo;
    }

    public void setShowImageInfo(boolean showImageInfo) {
        this.showImageInfo = showImageInfo;
    }

    public static Cursor getCursorForHandlePosition(HandlePosition handlePosition) {
        switch (handlePosition) {
            case NW:
                return Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
            case N:
                return Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
            case NE:
                return Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
            case E:
                return Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
            case SE:
                return Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
            case S:
                return Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
            case SW:
                return Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR);
            case W:
                return Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
        }
        return null;
    }

    /**
     * Updates the Cursor to reflect the current state of the component.
     */
    private void updateCursor() {
        Point pointer = getMousePosition();
        if (pointer != null && guidedInspectionControlAt(pointer.x, pointer.y) != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return;
        }
        if (selectionEnabled) {
            if (selectionMode == SelectionMode.Moving) {
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }
            else if (selectionMode == SelectionMode.Resizing) {
                setCursor(getCursorForHandlePosition(selectionActiveHandle));
            }
            else if (selectionMode == null && selection != null) {
                Point p = getMousePosition();
                if (p == null) {
                    return;
                }
                int x = p.x;
                int y = p.y;

                HandlePosition handlePosition = getSelectionHandleAtPosition(x, y);
                if (handlePosition != null) {
                    setCursor(getCursorForHandlePosition(handlePosition));
                }
                else if (selectionScaled.contains(x, y)) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
                else {
                    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                }
            }
            else {
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            }
        }
        else {
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }
    }

    /**
     * Capture the current image (unscaled, unmodified) and write it to disk.
     */
    private void captureSnapshot() {
        UiUtils.submitUiMachineTask(() -> {
            File dir = new File(Configuration.get().getConfigurationDirectory(), "snapshots");
            dir.mkdirs();
            DateFormat df = new SimpleDateFormat("YYYY-MM-dd_HH.mm.ss.SSS");
            File file = new File(dir, camera.getName() + "_" + df.format(new Date()) + ".png");
            ImageIO.write(camera.lightSettleAndCapture(), "png", file);
            flash();
        });
    }

    private void fireActionEvent(MouseEvent e) {
        if (actionListeners.isEmpty()) {
            return;
        }

        int x = e.getX();
        int y = e.getY();

        // Find the difference in X and Y from the center of the image
        // to the mouse click.
        double offsetX = (scaledWidth / 2.0D) - (x - imageX);
        double offsetY = (scaledHeight / 2.0D) - (y - imageY);

        // Invert the X so that the offsets represent a bottom left to
        // top right coordinate system.
        offsetX = -offsetX;

        // Scale the offsets by the units per pixel for the camera.
        offsetX *= scaledUnitsPerPixelX;
        offsetY *= scaledUnitsPerPixelY;

        // The offsets now represent the distance to move the camera
        // in the Camera's units per pixel's units.

        // Create a location in the Camera's units per pixel's units
        // and with the values of the offsets.
        Location offsets = camera.getUnitsPerPixelAtZ().
                derive(offsetX, offsetY, 0.0, 0.0);
        // Add the offsets to the Camera's position.
        Nozzle nozzle = MainFrame.get().getMachineControls().getSelectedNozzle();
        Location location = camera.getLocation(nozzle).add(offsets);
        CameraViewActionEvent action =
                new CameraViewActionEvent(CameraView.this, e.getX(), e.getY(),
                        e.getX() * scaledUnitsPerPixelX, e.getY() * scaledUnitsPerPixelY, location);
        for (CameraViewActionListener listener : new ArrayList<>(actionListeners)) {
            listener.actionPerformed(action);
        }
    }

    /**
     * Gets the offset from the center of the image in camera pixels given a set of coordinates in
     * the camera view component.
     *  
     * @param x - the x position in the camera's view component as returned by a mouse click
     * @param y - the y position in the camera's view component as returned by a mouse click
     * @return the offset in camera pixels from the center of the image in a bottom left to
     * top right coordinate system
     */
    public synchronized Point getCameraViewCenterPixelsFromXy(int x, int y) {
        Point2D.Double offset = CameraViewCoordinateTransform.centerOffset(x, y,
                imageX, imageY, scaledWidth, scaledHeight, scaleRatioX, scaleRatioY);
        return new Point((int) Math.round(offset.x), (int) Math.round(offset.y));
    }

    public synchronized Location getCameraViewCenterOffsetsFromXy(int x, int y) {
        Location unitsPerPixel = lastUnitsPerPixel == null
                ? camera.getUnitsPerPixelAtZ() : lastUnitsPerPixel;
        return getCameraViewCenterOffsetsFromXy(x, y, unitsPerPixel);
    }

    private synchronized Location getCameraViewCenterOffsetsFromXy(int x, int y,
            Location unitsPerPixel) {
        Point2D.Double offset = CameraViewCoordinateTransform.centerOffset(x, y,
                imageX, imageY, scaledWidth, scaledHeight,
                unitsPerPixel.getX() * scaleRatioX,
                unitsPerPixel.getY() * scaleRatioY);
        return new Location(unitsPerPixel.getUnits(),
                offset.x, offset.y, 0.0, 0.0);
    }

    private synchronized boolean isPointInsideCameraImage(int x, int y) {
        return CameraViewCoordinateTransform.contains(x, y, imageX, imageY,
                scaledWidth, scaledHeight);
    }

    private synchronized CameraViewPrecisionLocator.Target capturePrecisionTarget(int x, int y) {
        if (!isPointInsideCameraImage(x, y) || lastFrame == null) {
            return null;
        }
        Point2D.Double source = CameraViewCoordinateTransform.viewToSource(x, y,
                imageX, imageY, scaleRatioX, scaleRatioY);
        return CameraViewPrecisionLocator.capture(lastFrame, source.x, source.y);
    }

    private synchronized Resolution resolveVisionScaleAt(int x, int y) {
        if (!isPointInsideCameraImage(x, y) || lastFrame == null) {
            return null;
        }
        Point2D.Double source = CameraViewCoordinateTransform.viewToSource(x, y,
                imageX, imageY, scaleRatioX, scaleRatioY);
        double pixelOffsetX = source.x - lastFrame.getWidth() / 2.0;
        double pixelOffsetY = lastFrame.getHeight() / 2.0 - source.y;
        return SafetyWorkbenchVisionScale.resolve(getVisionScaleModel(), camera,
                camera.getLocation(), pixelOffsetX, pixelOffsetY);
    }

    private void showVisionScaleNotice(Resolution resolution) {
        if (resolution == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastVisionScaleNoticeAt < 1000) {
            return;
        }
        lastVisionScaleNoticeAt = now;
        if (resolution.isApplied()) {
            showDragJogInfo(String.format(Locale.ROOT,
                    "拖拽定位 · 3D 高度补偿：%s，顶面 Z %.3f mm",
                    resolution.getObject().name, resolution.getSurfaceZ()), 2600);
            return;
        }
        String message = null;
        if (resolution.getStatus() == Status.ModelNotCertified) {
            message = "3D 高度修正未启用：请保存并认证工作台高度模型。";
        }
        else if (resolution.getStatus() == Status.CameraNotCalibrated) {
            message = "3D 高度修正未启用：顶部相机尚未完成双高度像素比例标定。";
        }
        else if (resolution.getStatus() == Status.NoSurface) {
            message = "鼠标位置没有匹配到 3D 实体顶面，本次使用相机默认高度。";
        }
        if (message != null) {
            showDragJogInfo(message, 3000);
        }
    }

    private void showDragJogInfo(String message, long durationMs) {
        dragJogInfo = message;
        dragJogInfoUntil = System.currentTimeMillis() + Math.max(800, durationMs);
        repaint();
    }

    private void moveToClick(MouseEvent e) {
        Camera jogCamera = camera;
        long jogToken = screenJogGate.tryBegin();
        if (jogToken < 0 || precisionCorrectionBusy) {
            if (jogToken >= 0) {
                screenJogGate.failedOrTimedOut(jogToken);
            }
            TransientNotification.show(this,
                    "正在等待上一次移动完成并刷新画面，本次拖拽已忽略。", 1200);
            return;
        }
        CameraViewPrecisionLocator.Target precisionTarget =
                capturePrecisionTarget(e.getX(), e.getY());
        Resolution visionScale = resolveVisionScaleAt(e.getX(), e.getY());
        Location movementUnitsPerPixel = visionScale != null && visionScale.isApplied()
                ? visionScale.getUnitsPerPixel()
                : camera.getUnitsPerPixelAtZ();
        showVisionScaleNotice(visionScale);
        // Get the offset from the Camera view center in Camera's units.
        Location offsets = getCameraViewCenterOffsetsFromXy(e.getX(), e.getY(),
                movementUnitsPerPixel);
        if (precisionTarget != null) {
            Location offsetsInMm = offsets.convertToUnits(LengthUnit.Millimeters);
            double commandedDistanceMm = offsetsInMm.getLinearDistanceTo(
                    new Location(LengthUnit.Millimeters));
            pendingPrecisionJog = new PendingPrecisionJog(jogToken, jogCamera,
                    precisionTarget, movementUnitsPerPixel, commandedDistanceMm, zoom);
        }
        // And move there.
        UiUtils.submitUiMachineTask(() -> {
            moveByScreenOffset(jogCamera, offsets);
            return null;
        }, result -> {
            long completedAtFrame = previewFrameSequence.get();
            screenJogGate.motionCompleted(jogToken, completedAtFrame);
            scheduledExecutor.schedule(() -> {
                if (screenJogGate.failedOrTimedOut(jogToken)) {
                    clearPendingPrecisionJog(jogToken);
                }
            }, SCREEN_JOG_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }, throwable -> {
            screenJogGate.failedOrTimedOut(jogToken);
            clearPendingPrecisionJog(jogToken);
            showScreenJogFailure(throwable);
        });
    }

    private void moveByScreenOffset(Camera jogCamera, Location offsets) throws Exception {
        // For non-movable cameras, move the nozzle so that the selected position is centered.
        if (jogCamera.getHead() == null) {
            Nozzle nozzle = MainFrame.get().getMachineControls().getSelectedNozzle();
            Location currentLocation = nozzle.getLocation();
            Location location = currentLocation.subtract(offsets);
            location = currentLocation.derive(location, true, true, false, false);
            if (currentLocation.getLinearLengthTo(jogCamera.getLocation())
                    .compareTo(jogCamera.getRoamingRadius()) < 0
                    && location.getLinearLengthTo(jogCamera.getLocation())
                            .compareTo(jogCamera.getRoamingRadius()) < 0) {
                nozzle.moveTo(location, Motion.MotionOption.JogMotion);
            }
            else {
                MovableUtils.moveToLocationAtSafeZ(nozzle, location);
            }
        }
        else {
            Location location = jogCamera.getLocation().add(offsets);
            MovableUtils.moveToLocationAtSafeZ(jogCamera, location);
        }
        MovableUtils.fireTargetedUserAction(jogCamera, true);
    }

    private void clearPendingPrecisionJog(long token) {
        PendingPrecisionJog pending = pendingPrecisionJog;
        if (pending != null && pending.token == token) {
            pendingPrecisionJog = null;
        }
    }

    private void runPrecisionCorrection(PendingPrecisionJog precisionJog,
            BufferedImage settledFrame) {
        int searchRadiusX = CameraViewCoordinateTransform.precisionSearchRadius(
                settledFrame.getWidth(), precisionJog.viewZoom);
        int searchRadiusY = CameraViewCoordinateTransform.precisionSearchRadius(
                settledFrame.getHeight(), precisionJog.viewZoom);
        CameraViewPrecisionLocator.Result match = CameraViewPrecisionLocator.locateNear(
                settledFrame, precisionJog.target,
                settledFrame.getWidth() / 2.0, settledFrame.getHeight() / 2.0,
                searchRadiusX, searchRadiusY);
        if (match == null || match.confidence < CameraViewPrecisionLocator.MIN_CONFIDENCE) {
            precisionCorrectionBusy = false;
            showDragJogInfo("拖拽定位完成 · 目标纹理不足，未执行自动微调", 2600);
            return;
        }
        Point2D.Double residualPixels = CameraViewCoordinateTransform.centerOffset(
                match.sourceX, match.sourceY, 0, 0,
                settledFrame.getWidth(), settledFrame.getHeight(), 1, 1);
        Location upp = precisionJog.unitsPerPixel;
        Location correction = new Location(upp.getUnits(),
                residualPixels.x * upp.getX(), residualPixels.y * upp.getY(), 0, 0);
        Location correctionInMm = correction.convertToUnits(LengthUnit.Millimeters);
        Location unitsPerPixelInMm = upp.convertToUnits(LengthUnit.Millimeters);
        double correctionMm = correctionInMm
                .getLinearDistanceTo(new Location(LengthUnit.Millimeters));
        double onePixelMm = Math.max(Math.abs(unitsPerPixelInMm.getX()),
                Math.abs(unitsPerPixelInMm.getY()));
        if (correctionMm < onePixelMm) {
            precisionCorrectionBusy = false;
            return;
        }
        double correctionLimitMm = CameraViewCoordinateTransform.precisionCorrectionLimit(
                precisionJog.commandedDistanceMm, onePixelMm, precisionJog.viewZoom,
                MAX_PRECISION_CORRECTION_MM);
        if (!Double.isFinite(correctionMm) || correctionMm > correctionLimitMm) {
            precisionCorrectionBusy = false;
            showDragJogInfo(String.format(Locale.ROOT,
                    "拖拽定位完成 · 微调 %.3f mm 超过本次限值 %.3f mm，已忽略",
                    correctionMm, correctionLimitMm), 3000);
            return;
        }
        long correctionToken = screenJogGate.tryBegin();
        if (correctionToken < 0) {
            precisionCorrectionBusy = false;
            return;
        }
        UiUtils.submitUiMachineTask(() -> {
            moveByScreenOffset(precisionJog.camera, correction);
            return null;
        }, result -> {
            precisionCorrectionBusy = false;
            screenJogGate.motionCompleted(correctionToken, previewFrameSequence.get());
            scheduledExecutor.schedule(
                    () -> screenJogGate.failedOrTimedOut(correctionToken),
                    SCREEN_JOG_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            showDragJogInfo(String.format(Locale.ROOT,
                    "拖拽定位 · 已微调 %.3f mm，置信度 %.0f%%",
                    correctionMm, match.confidence * 100.0), 2600);
        }, throwable -> {
            precisionCorrectionBusy = false;
            screenJogGate.failedOrTimedOut(correctionToken);
            showScreenJogFailure(throwable);
        });
    }
    
    private void rotateToClick(MouseEvent e) {
        Insets ins = getInsets();
        int width = getWidth() - ins.left - ins.right;
        int height = getHeight() - ins.top - ins.bottom;

        double rotTargetHandleAngle = Math.toDegrees(Math.atan2(e.getY() - (height / 2), e.getX() - (width / 2)));
        rotTargetHandleAngle = Utils2D.normalizeAngle(rotTargetHandleAngle);

        // If the alt button is pressed snap to certain angles
        if(e.isAltDown()) {
            rotTargetHandleAngle = snapRotationAngleToTypicalAngles(rotTargetHandleAngle);
        }

        double targetAngle = Utils2D.normalizeAngle(-(rotTargetHandleAngle + 90));
        HeadMountable selectedTool = MainFrame.get().getMachineControls().getSelectedTool();

        UiUtils.submitUiMachineTask(() -> {
            Location location = selectedTool.getLocation();
            location = location.derive(null, null, null, targetAngle);
            MovableUtils.moveToLocationAtSafeZ(selectedTool, location);
            MovableUtils.fireTargetedUserAction(selectedTool, true);
            return null;
        }, result -> {
        }, throwable -> showScreenJogFailure(throwable));
    }

    private void showScreenJogFailure(Throwable throwable) {
        if (TransientNotification.isTransientMotionFailure(throwable)) {
            TransientNotification.showMotionFailure(this, throwable);
        }
        else {
            UiUtils.showError(this, "Error", throwable);
        }
    }

    private void toggleLight(MouseEvent e) {
        Actuator actuator = camera.getLightActuator();
        if (actuator == null) {
            return;
        }
        UiUtils.submitUiMachineTask(() -> {
            // Pass as Object for the generic behavior according to the actuator valueType.  
            boolean toggleState = actuator.isActuated() == null || !actuator.isActuated();
            actuator.actuate(toggleState);
            // Note, we cannot use MovableUtils.fireTargetedUserAction(), because this itself might 
            // turn the light on.
            camera.settleAndCapture();
        });
    }

    private void beginSelection(MouseEvent e) {
        // If we're not doing anything currently, we can start
        // a new operation.
        if (selectionMode == null) {
            int x = e.getX();
            int y = e.getY();

            // See if there is a handle under the cursor.
            HandlePosition handlePosition = getSelectionHandleAtPosition(x, y);
            if (handlePosition != null) {
                selectionMode = SelectionMode.Resizing;
                selectionActiveHandle = handlePosition;
            }
            // If not, perhaps they want to move the rectangle
            else if (selection != null && selectionScaled.contains(x, y)) {

                selectionMode = SelectionMode.Moving;
                // Store the distance between the rectangle's origin and
                // where they started moving it from.
                selectionStartX = x - selectionScaled.x;
                selectionStartY = y - selectionScaled.y;
            }
            // If not those, it's time to create a rectangle
            else {
                selectionMode = SelectionMode.Creating;
                selectionStartX = x;
                selectionStartY = y;
            }
        }
    }

    private void continueSelection(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();

        if (selectionMode == SelectionMode.Resizing) {
            int rx = selectionScaled.x;
            int ry = selectionScaled.y;
            int rw = selectionScaled.width;
            int rh = selectionScaled.height;

            if (selectionActiveHandle == HandlePosition.NW) {
                setScaledSelection(x, y, (rw - (x - rx)), (rh - (y - ry)));
            }
            else if (selectionActiveHandle == HandlePosition.NE) {
                setScaledSelection(rx, y, x - rx, (rh - (y - ry)));
            }
            else if (selectionActiveHandle == HandlePosition.N) {
                setScaledSelection(rx, y, rw, (rh - (y - ry)));
            }
            else if (selectionActiveHandle == HandlePosition.E) {
                setScaledSelection(rx, ry, rw + (x - (rx + rw)), rh);
            }
            else if (selectionActiveHandle == HandlePosition.SE) {
                setScaledSelection(rx, ry, rw + (x - (rx + rw)), rh + (y - (ry + rh)));
            }
            else if (selectionActiveHandle == HandlePosition.S) {
                setScaledSelection(rx, ry, rw, rh + (y - (ry + rh)));
            }
            else if (selectionActiveHandle == HandlePosition.SW) {
                setScaledSelection(x, ry, (rw - (x - rx)), rh + (y - (ry + rh)));
            }
            else if (selectionActiveHandle == HandlePosition.W) {
                setScaledSelection(x, ry, (rw - (x - rx)), rh);
            }
        }
        else if (selectionMode == SelectionMode.Moving) {
            setScaledSelection(x - selectionStartX, y - selectionStartY, selectionScaled.width,
                    selectionScaled.height);
        }
        else if (selectionMode == SelectionMode.Creating) {
            int sx = selectionStartX;
            int sy = selectionStartY;
            int w = x - sx;
            int h = y - sy;
            setScaledSelection(sx, sy, w, h);
        }
        updateCursor();
        repaint();
    }

    private void endSelection() {
        selectionMode = null;
        selectionActiveHandle = null;
    }
    
    private void dragJoggingBegin(MouseEvent e) {
        if (! isTogglingLight()) {
            this.dragJogging = true;
            this.dragJoggingStart = e;
            this.dragJoggingTarget = e;
            repaint();
        }
    }
    
    private void dragJoggingContinue(MouseEvent e) {
        if (! isTogglingLight()) {
            this.dragJoggingTarget = e;
            repaint();
        }
    }
    
    private void dragJoggingEnd(MouseEvent e) {
        if (! isTogglingLight()) {
            int startX = dragJoggingStart.getX();
            int startY = dragJoggingStart.getY();
            
            this.dragJogging = false;
            this.dragJoggingStart = null;
            this.dragJoggingTarget = null;
            repaint();
            
            if (isPointInsideDragJogRotationHandle(startX, startY)) {
                rotateToClick(e);
            }
            else {
                moveToClick(e);
            }
        }
    }

    private boolean isDragJogging() {
        return this.dragJogging;
    }

    private void beginTogglingLight() {
        lightToggling = true;
        repaint();
    }
    private void endTogglingLight() {
        lightToggling = false;
        repaint();
    }
    private boolean isTogglingLight() {
        return lightToggling;
    }


    private MouseListener mouseListener = new MouseAdapter() {
 
        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.isPopupTrigger() || e.isShiftDown() || SwingUtilities.isRightMouseButton(e)) {
                return;
            }
            if (guidedInspectionControlClickConsumed) {
                guidedInspectionControlClickConsumed = false;
                return;
            }
            // double click captures an image from the camera and writes it to disk.
            if (e.getClickCount() == 2) {
                captureSnapshot();
            }
            else {
                fireActionEvent(e);
            }
        }

        @Override
        public void mousePressed(MouseEvent e) {
            if (e.isPopupTrigger()) {
                new CameraViewPopupMenu(CameraView.this).show(e.getComponent(), e.getX(), e.getY());
                return;
            }
            else if (e.isShiftDown()) {
                moveToClick(e);
            }
            else if (guidedInspectionControlAt(e.getX(), e.getY()) != null) {
                guidedInspectionControlPressed = guidedInspectionControlAt(e.getX(), e.getY());
                repaint();
            }
            else if (isPointInsideLightToggle(e.getX(), e.getY())) {
                beginTogglingLight();
            }
            else if (selectionEnabled) {
                beginSelection(e);
            }
        }
        @Override
        public void mouseReleased(MouseEvent e) {
            if (e.isPopupTrigger() && !isDragJogging()) {
                new CameraViewPopupMenu(CameraView.this).show(e.getComponent(), e.getX(), e.getY());
                return;
            }
            else if (guidedInspectionControlPressed != null) {
                GuidedInspectionControl released = guidedInspectionControlAt(e.getX(), e.getY());
                GuidedInspectionControl activated = guidedInspectionControlPressed;
                guidedInspectionControlPressed = null;
                guidedInspectionControlClickConsumed = true;
                repaint();
                if (released == activated && guidedInspectionControlListener != null) {
                    if (activated == GuidedInspectionControl.Next) {
                        SwingUtilities.invokeLater(
                                guidedInspectionControlListener::completeAndNext);
                    }
                    else {
                        SwingUtilities.invokeLater(
                                guidedInspectionControlListener::completeAndExit);
                    }
                }
            }
            else if (isDragJogging()) {
                dragJoggingEnd(e);
            }
            else if (isTogglingLight() && isPointInsideLightToggle(e.getX(), e.getY())) {
                toggleLight(e);
            }
            else {
                endSelection();
            }
            endTogglingLight();
        }
    };

    private MouseMotionListener mouseMotionListener = new MouseMotionAdapter() {
        @Override
        public void mouseMoved(MouseEvent e) {
            GuidedInspectionControl previous = guidedInspectionControlHover;
            guidedInspectionControlHover = guidedInspectionControlAt(e.getX(), e.getY());
            if (previous != guidedInspectionControlHover) {
                repaint();
            }
            updateCursor();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (selectionEnabled) {
                continueSelection(e);
            }
            else if (!isDragJogging()) {
                dragJoggingBegin(e);
            }
            else if (isDragJogging()) {
                dragJoggingContinue(e);
            }
        }
    };

    private ComponentListener componentListener = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            calculateScalingData();
        }
    };

    private MouseWheelListener mouseWheelListener = new MouseWheelListener() {
        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            int modifiers = e.getModifiersEx();
            boolean ctrlDown = (modifiers & InputEvent.CTRL_DOWN_MASK) != 0;
            if (!ctrlDown) { // Scroll wheel without Ctrl changes the zoom factor
                double zoomInc = Math.max(zoomIncPerMouseWheelTick,
                        // When best-scale is selected, we can only zoom by integers
                        renderingQuality == RenderingQuality.BestScale ? 2.0 : 0);
                zoom = zoom * Math.pow(zoomInc, - e.getPreciseWheelRotation());
                zoom = Math.max(zoom, 1.0d);
                zoom = Math.min(zoom, 64d);
            }
            calculateScalingData();
            repaint();
        }
    };

    public CameraViewSelectionTextDelegate pixelsAndUnitsTextSelectionDelegate =
            new CameraViewSelectionTextDelegate() {
                @Override
                public String getSelectionText(CameraView cameraView) {
                    Location upp = camera.getUnitsPerPixelAtZ();
                    double widthInUnits = selection.width * upp.getX();
                    double heightInUnits = selection.height * upp.getY();

                    String text = String.format(Locale.US, "%dpx, %dpx\n%2.3f%s, %2.3f%s",
                            (int) selection.getWidth(), (int) selection.getHeight(), widthInUnits,
                            upp.getUnits().getShortName(), heightInUnits,
                            upp.getUnits().getShortName());
                    return text;
                }
            };
            
    // From https://stackoverflow.com/questions/3793400/is-there-a-function-in-java-to-get-moving-average/42407811#42407811
    static class MovingAverage {
        private long [] window;
        private int n, insert;
        private long sum;

        public MovingAverage(int size) {
            window = new long[size];
            insert = 0;
            sum = 0;
        }

        public double next(long val) {
            if (n < window.length)  {
                n++;
            }
            sum -= window[insert];
            sum += val;
            window[insert] = val;
            insert = (insert + 1) % window.length;
            return (double)sum / n;
        }
    }            
}
