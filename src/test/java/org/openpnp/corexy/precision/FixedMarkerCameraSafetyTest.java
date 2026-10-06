package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.ReferenceHead;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.driver.NullDriver;
import org.openpnp.machine.reference.driver.ReferenceAdvancedMotionPlanner;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion.MoveToCommand;
import org.openpnp.spi.Axis;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Machine;

/** Exercises production geometry with capture and every driver motion operation prohibited. */
class FixedMarkerCameraSafetyTest {
    @TempDir
    Path temporary;
    private ReferenceMachine machine;
    private GeometryPlanner planner;
    private ReferenceControllerAxis x, y;
    private GeometryCamera camera;
    private NoMotionDriver driver;
    private OpenPnpCalibrationMachine backend;

    @BeforeEach
    void geometryFixture() throws Exception {
        Configuration.initialize(temporary.toFile());
        machine = new ReferenceMachine();
        Configuration.get().setMachine(machine);
        driver = new NoMotionDriver();
        machine.addDriver(driver);
        x = axis(Axis.Type.X);
        y = axis(Axis.Type.Y);
        machine.addAxis(x);
        machine.addAxis(y);
        planner = new GeometryPlanner();
        machine.setMotionPlanner(planner);
        ReferenceHead head = new ReferenceHead();
        machine.addHead(head);
        camera = new GeometryCamera();
        camera.setAxisX(x);
        camera.setAxisY(y);
        camera.setUnitsPerPixel(new Location(LengthUnit.Millimeters, .04, .03, 0, 0));
        head.addCamera(camera);
        PrecisionRuntime.deactivate();
        ToolPointRuntime.setModels(Collections.emptyMap());
        backend = new OpenPnpCalibrationMachine(Configuration.get());
    }

    @AfterEach
    void noPhysicalOperationsWereRequested() throws Exception {
        assertEquals(0, driver.moves, "Geometry preflight must not request movement.");
        assertEquals(0, driver.homes, "Geometry preflight must not seek end stops.");
        assertEquals(0, driver.offsetChanges, "Geometry preflight must not change driver coordinates.");
        assertEquals(0, camera.captures, "Geometry preflight must not open or capture a camera.");
        PrecisionRuntime.deactivate();
        ToolPointRuntime.setModels(Collections.emptyMap());
        camera.close();
    }

    @Test
    void homeAtEitherTravelBoundaryKeepsTheCompensationAndHomingDriftReserve() throws Exception {
        CalibrationRequest request = settings().toRequest();
        Location lowView = homeView(request);
        AxesLocation lowRaw = raw(lowView);
        assertEquals(1 + request.maximumOffset, lowRaw.getCoordinate(x), 1e-9);
        assertEquals(1 + request.maximumOffset, lowRaw.getCoordinate(y), 1e-9);
        assertEquals(request.planeZ, lowView.getZ(), 1e-9);

        FixedMarkerSetup high = settings();
        high.markers.get(0).x = 300;
        high.markers.get(0).y = 300;
        request = high.toRequest();
        AxesLocation highRaw = raw(homeView(request));
        assertEquals(300 - 1 - request.maximumOffset, highRaw.getCoordinate(x), 1e-9);
        assertEquals(300 - 1 - request.maximumOffset, highRaw.getCoordinate(y), 1e-9);

        FixedMarkerSetup largerDrift = settings();
        largerDrift.maximumHomeOffset = 2.4;
        largerDrift.homeSearchRadius = 3;
        request = largerDrift.toRequest();
        AxesLocation driftRaw = raw(homeView(request));
        assertEquals(2.4 + request.maximumOffset, driftRaw.getCoordinate(x), 1e-9);
        assertEquals(2.4 + request.maximumOffset, driftRaw.getCoordinate(y), 1e-9);
    }

    @Test
    void nonzeroCameraHeadOffsetsAreMappedInRawAxesWhileTheRequestedMeasurementZIsPreserved() throws Exception {
        camera.setHeadOffsets(new Location(LengthUnit.Millimeters, 4, -3, 8, 0));
        CalibrationRequest request = settings().toRequest();
        request.planeZ = 7.5;
        request.fixedMarkers.planeZ = request.planeZ;
        Location view = homeView(request);
        AxesLocation raw = raw(view);
        assertEquals(1.2, raw.getCoordinate(x), 1e-9);
        assertEquals(3, raw.getCoordinate(y), 1e-9);
        assertEquals(5.2, view.getX(), 1e-9);
        assertEquals(0, view.getY(), 1e-9);
        assertEquals(7.5, view.getZ(), 1e-9, "A camera head Z offset must not replace the marker measurement plane.");
        assertEquals(0, view.getRotation(), 1e-9);
        assertTrue(camera.isReachable(view));
    }

    @Test
    void insufficientFieldOfViewAndTooFewMarkerPixelsFailBeforeAnyMotion() throws Exception {
        CalibrationRequest request = settings().toRequest();
        assertDoesNotThrow(() -> homeView(request), "A wide field can observe the boundary datum away from image center.");
        camera.setUnitsPerPixel(new Location(LengthUnit.Millimeters, .005, .005, 0, 0));
        IllegalStateException narrow = assertThrows(IllegalStateException.class, () -> homeView(request));
        assertTrue(narrow.getMessage().contains("视野不足"));
        camera.setUnitsPerPixel(new Location(LengthUnit.Millimeters, .2, .2, 0, 0));
        assertThrows(IllegalStateException.class, () -> homeView(request), "A five-pixel marker cannot support the visual datum detector.");
        camera.setUnitsPerPixel(new Location(LengthUnit.Millimeters, 0, .03, 0, 0));
        assertThrows(IllegalStateException.class, () -> homeView(request), "A missing X scale must never bypass field-of-view checks.");
    }

    @Test
    void incompleteThreeDimensionalCalibrationFallsBackToTheValidPrimaryScale() throws Exception {
        camera.setEnableUnitsPerPixel3D(true);
        camera.setUnitsPerPixelSecondary(new Location(LengthUnit.Millimeters, 0, 0, 10, 0));
        assertFalse(camera.isUnitsPerPixelAtZCalibrated());
        CalibrationRequest request = settings().toRequest();
        request.planeZ = 12;
        request.fixedMarkers.planeZ = request.planeZ;
        Location view = homeView(request);
        assertEquals(12, view.getZ(), 1e-9);
        assertEquals(.04, camera.getUnitsPerPixel(new Length(12, LengthUnit.Millimeters)).getX(), 1e-9);
    }

    @Test
    void calibratedThreeDimensionalFieldOfViewUsesTheRequestedMarkerPlaneInsteadOfTheDefaultZ() throws Exception {
        camera.setUnitsPerPixelPrimary(new Location(LengthUnit.Millimeters, .002, .002, 0, 0));
        camera.setUnitsPerPixelSecondary(new Location(LengthUnit.Millimeters, .04, .04, 10, 0));
        camera.setCameraPrimaryZ(new Length(0, LengthUnit.Millimeters));
        camera.setCameraSecondaryZ(new Length(0, LengthUnit.Millimeters));
        camera.setDefaultZ(new Length(0, LengthUnit.Millimeters));
        camera.setEnableUnitsPerPixel3D(true);
        assertTrue(camera.isUnitsPerPixelAtZCalibrated());
        assertEquals(.002, camera.getUnitsPerPixel().getX(), 1e-9, "The default viewing plane has insufficient field of view.");
        FixedMarkerSetup setup = settings();
        setup.planeZ = 10;
        CalibrationRequest request = setup.toRequest();
        Location view = homeView(request);
        assertEquals(10, view.getZ(), 1e-9);
        assertEquals(10, camera.lastPlaneZ, 1e-9);

        request.planeZ = 0;
        request.fixedMarkers.planeZ = 0;
        assertThrows(IllegalStateException.class, () -> homeView(request), "At the narrower calibrated plane the same datum must be rejected.");
    }

    @Test
    void signedCameraScalesKeepTheHomeMarkerInsideTheImageAfterAxisMapping() throws Exception {
        camera.setUnitsPerPixel(new Location(LengthUnit.Millimeters, -.04, -.03, 0, 0));
        CalibrationRequest request = settings().toRequest();
        Location view = homeView(request);
        assertEquals(1.2, raw(view).getCoordinate(x), 1e-9);
        assertEquals(1.2, raw(view).getCoordinate(y), 1e-9);
        double pixelX = camera.getWidth() / 2.0 + (request.originX - view.getX()) / -.04;
        double pixelY = camera.getHeight() / 2.0 - (request.originY - view.getY()) / -.03;
        assertTrue(pixelX > 0 && pixelX < camera.getWidth());
        assertTrue(pixelY > 0 && pixelY < camera.getHeight());
    }

    @Test
    void completePlanCanObserveOnlyItsFixedHomeOffCenterAndChecksAllFourOtherMarkerMargins() throws Exception {
        CalibrationRequest request = settings().toRequest();
        setRequest(request);
        backend.validatePlan(new CalibrationPlan(request));
        assertTrue(planner.checked.stream().anyMatch(location ->
                Math.abs(location.getCoordinate(x) - 1.2) < 1e-9
                && Math.abs(location.getCoordinate(y) - 1.2) < 1e-9), "The Home endpoint must use its reserved off-center aiming position.");

        for (int markerIndex = 1; markerIndex < 5; markerIndex++) {
            FixedMarkerSetup setup = settings();
            FixedMarkerSetup.Marker marker = setup.markers.get(markerIndex);
            marker.x = .5;
            request = setup.toRequest();
            setRequest(request);
            CalibrationPlan.Path path = new CalibrationPlan.Path("direct-other-" + markerIndex,
                    marker.id, marker.x, marker.y, 0, 0, .35, 1, 0, 0, 1, 0);
            path.direct = true;
            IllegalStateException rejection = assertThrows(IllegalStateException.class,
                    () -> backend.validatePlan(new CalibrationPlan(Collections.singletonList(path))));
            assertTrue(rejection.getMessage().contains("安全余量"),
                    "Other marker " + markerIndex + " must not inherit the Home boundary exemption.");
        }
    }

    @Test
    void aConditioningExcursionNearTheBoundaryIsRejectedEvenWhenTheMarkerEndpointIsSafe() throws Exception {
        FixedMarkerSetup setup = settings();
        FixedMarkerSetup.Marker marker = setup.markers.get(1);
        marker.x = 48;
        CalibrationRequest request = setup.toRequest();
        setRequest(request);
        CalibrationPlan.Path path = new CalibrationPlan.Path("unsafe-conditioning", marker.id,
                marker.x, marker.y, 1, 1, .35, 1, -1, -1, 1, 0);
        assertEquals(1, path.points(request.conditioningTravel)[0][0], 1e-9);
        assertTrue(marker.x > 1 + request.maximumOffset);
        IllegalStateException rejection = assertThrows(IllegalStateException.class,
                () -> backend.validatePlan(new CalibrationPlan(Collections.singletonList(path))));
        assertTrue(rejection.getMessage().contains("安全余量"));
    }

    @Test
    void disabledOrTooSmallSoftLimitsFailBeforeAnyViewingTargetIsReturned() throws Exception {
        CalibrationRequest request = settings().toRequest();
        x.setSoftLimitLowEnabled(false);
        assertThrows(IllegalStateException.class, () -> homeView(request));
        x.setSoftLimitLowEnabled(true);
        x.setSoftLimitHigh(new Length(2, LengthUnit.Millimeters));
        assertThrows(IllegalStateException.class, () -> homeView(request));
    }

    private AxesLocation raw(Location location) throws Exception {
        return camera.toRaw(camera.toHeadLocation(location));
    }

    private Location homeView(CalibrationRequest request) throws Exception {
        Method method = OpenPnpCalibrationMachine.class.getDeclaredMethod("homeViewingLocation", CalibrationRequest.class);
        method.setAccessible(true);
        try {
            return (Location) method.invoke(backend, request);
        }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        }
    }

    private void setRequest(CalibrationRequest request) throws Exception {
        Field field = OpenPnpCalibrationMachine.class.getDeclaredField("request");
        field.setAccessible(true);
        field.set(backend, request);
    }

    private ReferenceControllerAxis axis(Axis.Type type) {
        ReferenceControllerAxis axis = new ReferenceControllerAxis();
        axis.setType(type);
        axis.setLetter(type.name());
        axis.setDriver(driver);
        axis.setSoftLimitLow(new Length(0, LengthUnit.Millimeters));
        axis.setSoftLimitHigh(new Length(300, LengthUnit.Millimeters));
        axis.setSoftLimitLowEnabled(true);
        axis.setSoftLimitHighEnabled(true);
        return axis;
    }

    private static FixedMarkerSetup settings() {
        FixedMarkerSetup setup = new FixedMarkerSetup();
        double[][] coordinates = {{0, 0}, {100, 100}, {140, 100}, {140, 140}, {100, 140}};
        for (int index = 0; index < coordinates.length; index++) {
            setup.markers.add(new FixedMarkerSetup.Marker("ref-" + index, index == 0 ? "Home" : "覆盖点 " + index,
                    coordinates[index][0], coordinates[index][1]));
        }
        setup.planeZ = 7;
        return setup;
    }

    private static final class GeometryCamera extends SimulatedUpCamera {
        int captures;
        double lastPlaneZ;

        @Override
        public synchronized int getWidth() {
            return 640;
        }

        @Override
        public synchronized int getHeight() {
            return 480;
        }

        @Override
        public Location getUnitsPerPixel(Length viewingPlaneZ) {
            lastPlaneZ = viewingPlaneZ == null ? Double.NaN : viewingPlaneZ.convertToUnits(LengthUnit.Millimeters).getValue();
            return super.getUnitsPerPixel(viewingPlaneZ);
        }

        @Override
        public BufferedImage internalCapture() {
            captures++;
            throw new AssertionError("Camera geometry test must never request image capture.");
        }
    }

    private static final class NoMotionDriver extends NullDriver {
        int moves, homes, offsetChanges;

        @Override
        public void moveTo(HeadMountable headMountable, MoveToCommand motion) {
            moves++;
            throw new AssertionError("Camera geometry test must never move axes.");
        }

        @Override
        public void home(Machine machine) {
            homes++;
            throw new AssertionError("Camera geometry test must never Home axes.");
        }

        @Override
        public void setGlobalOffsets(Machine machine, AxesLocation location) {
            offsetChanges++;
            throw new AssertionError("Camera geometry test must never rewrite coordinates.");
        }
    }

    private static final class GeometryPlanner extends ReferenceAdvancedMotionPlanner {
        final List<AxesLocation> checked = new ArrayList<>();

        @Override
        public boolean isValidLocation(HeadMountable headMountable, AxesLocation axesLocation) {
            checked.add(axesLocation);
            return super.isValidLocation(headMountable, axesLocation);
        }
    }
}
