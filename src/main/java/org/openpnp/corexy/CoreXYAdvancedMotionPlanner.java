package org.openpnp.corexy;

import org.openpnp.machine.reference.driver.ReferenceAdvancedMotionPlanner;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion;
import org.openpnp.model.Motion.MotionOption;
import org.openpnp.spi.Axis;
import org.openpnp.spi.ControllerAxis;
import org.openpnp.spi.CoordinateAxis;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;

/**
 * External CoreXY compensation planner for an otherwise unmodified OpenPnP.
 *
 * The configured offsets are persistent direction-state offsets. They are not
 * added repeatedly. A changed A or B state generates one final settling move.
 */
public class CoreXYAdvancedMotionPlanner extends ReferenceAdvancedMotionPlanner {
    @Attribute(required = false)
    private boolean coreXYCompensationEnabled;

    @Attribute(required = false)
    private String coreXYCompensationXAxisId;

    @Attribute(required = false)
    private String coreXYCompensationYAxisId;

    @Element(required = false)
    private Length coreXYCompensationMinMoveA = millimeters(15.0);

    @Element(required = false)
    private Length coreXYCompensationMinMoveB = millimeters(15.0);

    @Element(required = false)
    private Location coreXYCompensationAPositiveOffset = zeroLocation();

    @Element(required = false)
    private Location coreXYCompensationANegativeOffset = zeroLocation();

    @Element(required = false)
    private Location coreXYCompensationBPositiveOffset = zeroLocation();

    @Element(required = false)
    private Location coreXYCompensationBNegativeOffset = zeroLocation();

    @Attribute(required = false)
    private double coreXYCompensationSpeedFactor = 0.25;

    private transient CoreXYStateModel stateModel = new CoreXYStateModel();
    private transient AxesLocation lastAOffset = new AxesLocation();
    private transient AxesLocation lastBOffset = new AxesLocation();

    private static Length millimeters(double value) {
        return new Length(value, LengthUnit.Millimeters);
    }

    private static Location zeroLocation() {
        return new Location(LengthUnit.Millimeters);
    }

    private CoreXYStateModel stateModel() {
        if (stateModel == null) {
            stateModel = new CoreXYStateModel();
        }
        return stateModel;
    }

    @Override
    protected synchronized AxesLocation createBacklashCompensatedMotion(
            HeadMountable hm, double speed, AxesLocation currentLocation,
            AxesLocation newLocation, MotionOption... options) {
        if (LegacyCompensationInterlock.isExclusive()) {
            requireNeutralCoreXYOffset();
            coreXYCompensationEnabled = false;
            resetCoreXYCompensationState();
            return super.createBacklashCompensatedMotion(hm, speed, currentLocation, newLocation, options);
        }
        AxesLocation previousOffset = getActiveCoreXYOffset();

        // Present the previous physical CoreXY offset to the stock planner. The
        // logical segment stays unchanged, so stock backlash handling is retained.
        AxesLocation physicalCurrent = currentLocation.add(previousOffset);
        AxesLocation physicalTargetAtPreviousState = newLocation.add(previousOffset);
        AxesLocation stockEnd = super.createBacklashCompensatedMotion(
                hm, speed, physicalCurrent, physicalTargetAtPreviousState, options);

        AxesLocation segment = currentLocation.motionSegmentTo(newLocation);
        AxesLocation updatedOffset = updateCoreXYState(segment);
        if (!sameCoordinates(previousOffset, updatedOffset)) {
            AxesLocation compensatedEnd = stockEnd.subtract(previousOffset).add(updatedOffset);
            addMotion(hm, Math.min(speed, coreXYCompensationSpeedFactor),
                    stockEnd, compensatedEnd, Motion.optionFlags(options));
            stockEnd = compensatedEnd;
        }
        return stockEnd;
    }

    private AxesLocation updateCoreXYState(AxesLocation segment) {
        Axis xAxis = getMachine().getAxis(coreXYCompensationXAxisId);
        Axis yAxis = getMachine().getAxis(coreXYCompensationYAxisId);
        if (!coreXYCompensationEnabled || !isValidAxisPair(xAxis, yAxis)) {
            stateModel().reset();
            lastAOffset = new AxesLocation();
            lastBOffset = new AxesLocation();
            return new AxesLocation();
        }

        stateModel().update(
                segment.getCoordinate(xAxis),
                segment.getCoordinate(yAxis),
                coreXYCompensationMinMoveA.convertToUnits(AxesLocation.getUnits()).getValue(),
                coreXYCompensationMinMoveB.convertToUnits(AxesLocation.getUnits()).getValue());

        if (stateModel().getAState() != 0) {
            lastAOffset = locationToAxes(
                    stateModel().getAState() > 0
                            ? coreXYCompensationAPositiveOffset
                            : coreXYCompensationANegativeOffset,
                    xAxis, yAxis);
        }
        if (stateModel().getBState() != 0) {
            lastBOffset = locationToAxes(
                    stateModel().getBState() > 0
                            ? coreXYCompensationBPositiveOffset
                            : coreXYCompensationBNegativeOffset,
                    xAxis, yAxis);
        }
        return getActiveCoreXYOffset();
    }

    private boolean isValidAxisPair(Axis xAxis, Axis yAxis) {
        return xAxis instanceof ControllerAxis
                && yAxis instanceof ControllerAxis
                && xAxis != yAxis
                && ((ControllerAxis) xAxis).getDriver() != null
                && ((ControllerAxis) xAxis).getDriver() == ((ControllerAxis) yAxis).getDriver();
    }

    private AxesLocation locationToAxes(Location location, Axis xAxis, Axis yAxis) {
        if (location == null) {
            return new AxesLocation();
        }
        Location millimeterLocation = location.convertToUnits(LengthUnit.Millimeters);
        return new AxesLocation(xAxis, millimeterLocation.getX())
                .put(new AxesLocation(yAxis, millimeterLocation.getY()));
    }

    private boolean sameCoordinates(AxesLocation first, AxesLocation second) {
        return first.matches(second) && second.matches(first);
    }

    private synchronized AxesLocation getActiveCoreXYOffset() {
        return lastAOffset.add(lastBOffset);
    }

    @Override
    public synchronized void setGlobalOffsets(AxesLocation axesLocation) throws Exception {
        AxesLocation activeOffset = getActiveCoreXYOffset();
        AxesLocation physicalLocation = new AxesLocation(
                axesLocation.getAxes(),
                axis -> axesLocation.getLengthCoordinate(axis)
                        .add(activeOffset.getLengthCoordinate(axis)));
        super.setGlobalOffsets(physicalLocation);
        axesLocation.setToCoordinates();
    }

    @Override
    public synchronized void waitForCompletion(HeadMountable hm, CompletionType completionType)
            throws Exception {
        super.waitForCompletion(hm, completionType);
        if (completionType.isEnforcingStillstand()) {
            AxesLocation activeOffset = getActiveCoreXYOffset();
            for (Axis axis : activeOffset.getAxes()) {
                if (axis instanceof CoordinateAxis) {
                    CoordinateAxis coordinateAxis = (CoordinateAxis) axis;
                    coordinateAxis.setLengthCoordinate(coordinateAxis.getLengthCoordinate()
                            .subtract(activeOffset.getLengthCoordinate(axis)));
                }
            }
        }
    }

    @Override
    public synchronized void home() throws Exception {
        resetCoreXYCompensationState();
        super.home();
        resetCoreXYCompensationState();
    }

    @Override
    public synchronized void unhome() {
        resetCoreXYCompensationState();
        super.unhome();
    }

    public synchronized void resetCoreXYCompensationState() {
        stateModel().reset();
        lastAOffset = new AxesLocation();
        lastBOffset = new AxesLocation();
    }

    public boolean isCoreXYCompensationConfigurationValid() {
        return isValidAxisPair(
                getMachine().getAxis(coreXYCompensationXAxisId),
                getMachine().getAxis(coreXYCompensationYAxisId));
    }

    public int getCoreXYCompensationAState() {
        return stateModel().getAState();
    }

    public int getCoreXYCompensationBState() {
        return stateModel().getBState();
    }

    public boolean isCoreXYCompensationEnabled() {
        return coreXYCompensationEnabled;
    }

    public void setCoreXYCompensationEnabled(boolean enabled) {
        if (enabled) { LegacyCompensationInterlock.assertLegacyPlannerEnableAllowed(); }
        coreXYCompensationEnabled = enabled;
    }

    public synchronized void requireNeutralCoreXYOffset() {
        if (!sameCoordinates(getActiveCoreXYOffset(), new AxesLocation())) {
            throw new IllegalStateException("旧 CoreXY 规划器仍有活动偏移，请先 Home 再切换。");
        }
    }

    public String getCoreXYCompensationXAxisId() {
        return coreXYCompensationXAxisId;
    }

    public void setCoreXYCompensationXAxisId(String axisId) {
        coreXYCompensationXAxisId = axisId;
    }

    public String getCoreXYCompensationYAxisId() {
        return coreXYCompensationYAxisId;
    }

    public void setCoreXYCompensationYAxisId(String axisId) {
        coreXYCompensationYAxisId = axisId;
    }

    public Length getCoreXYCompensationMinMoveA() {
        return coreXYCompensationMinMoveA;
    }

    public void setCoreXYCompensationMinMoveA(Length value) {
        coreXYCompensationMinMoveA = requireLength(value, "A threshold");
    }

    public Length getCoreXYCompensationMinMoveB() {
        return coreXYCompensationMinMoveB;
    }

    public void setCoreXYCompensationMinMoveB(Length value) {
        coreXYCompensationMinMoveB = requireLength(value, "B threshold");
    }

    public Location getCoreXYCompensationAPositiveOffset() {
        return coreXYCompensationAPositiveOffset;
    }

    public void setCoreXYCompensationAPositiveOffset(Location value) {
        coreXYCompensationAPositiveOffset = requireLocation(value, "A+ offset");
    }

    public Location getCoreXYCompensationANegativeOffset() {
        return coreXYCompensationANegativeOffset;
    }

    public void setCoreXYCompensationANegativeOffset(Location value) {
        coreXYCompensationANegativeOffset = requireLocation(value, "A- offset");
    }

    public Location getCoreXYCompensationBPositiveOffset() {
        return coreXYCompensationBPositiveOffset;
    }

    public void setCoreXYCompensationBPositiveOffset(Location value) {
        coreXYCompensationBPositiveOffset = requireLocation(value, "B+ offset");
    }

    public Location getCoreXYCompensationBNegativeOffset() {
        return coreXYCompensationBNegativeOffset;
    }

    public void setCoreXYCompensationBNegativeOffset(Location value) {
        coreXYCompensationBNegativeOffset = requireLocation(value, "B- offset");
    }

    public double getCoreXYCompensationSpeedFactor() {
        return coreXYCompensationSpeedFactor;
    }

    public void setCoreXYCompensationSpeedFactor(double value) {
        if (!Double.isFinite(value) || value <= 0.0 || value > 1.0) {
            throw new IllegalArgumentException("Compensation speed factor must be in (0, 1].");
        }
        coreXYCompensationSpeedFactor = value;
    }

    private Length requireLength(Length value, String name) {
        if (value == null || !Double.isFinite(value.getValue())) {
            throw new IllegalArgumentException(name + " must be finite.");
        }
        return value;
    }

    private Location requireLocation(Location value, String name) {
        if (value == null
                || !Double.isFinite(value.getX())
                || !Double.isFinite(value.getY())) {
            throw new IllegalArgumentException(name + " must contain finite X and Y values.");
        }
        return value;
    }
}
