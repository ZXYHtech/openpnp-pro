package org.openpnp.machine.reference.vision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.openpnp.machine.reference.driver.ReferenceAdvancedMotionPlanner;
import org.openpnp.machine.reference.safety.*;
import org.openpnp.model.*;
import org.openpnp.spi.*;
import org.openpnp.spi.Locatable.LocationOption;

/** N2 exposure, shared-cam handoff, N1 exposure, and rising exit in one bounded motion plan. */
public final class ContinuousFlyingTrajectory {
    public final Nozzle first, second;
    public final Location start;
    public final List<Location> destinations;
    public final List<Nozzle> tools;
    public final double velocity;
    public final List<Motion> preview;
    private final SafetyWorkbenchModel live, frozen;
    private final long revision;

    public ContinuousFlyingTrajectory(ReferenceBottomVision vision, Camera camera, Nozzle first, Part firstPart,
            double firstAngle, Nozzle second, Part secondPart, double secondAngle, double velocity) throws Exception {
        this.first = first;
        this.second = second;
        this.velocity = velocity;
        if (first == second || first.getHead() != second.getHead() || camera.getHead() != null)
            { throw new Exception("连续飞拍需要同一贴装头和固定底部相机"); }
        for (Nozzle nozzle : Arrays.asList(first, second)) {
            String issue = DynamicSafeMotionPlanner.continuousVisionIssue(nozzle);
            if (!issue.isEmpty()) { throw new Exception(issue); }
        }
        Machine machine = first.getHead().getMachine();
        if (!(machine.getMotionPlanner() instanceof ReferenceAdvancedMotionPlanner)
                || !((ReferenceAdvancedMotionPlanner)machine.getMotionPlanner()).isAllowContinuousMotion())
            { throw new Exception("连续飞拍需要启用原生连续运动规划"); }
        for (Driver driver : first.getMappedAxes(machine).getAxesDrivers(machine))
            { if (!driver.getMotionControlType().isSupportingUncoordinated())
                { throw new Exception("驱动不支持连续速度曲线"); } }
        Location a = vision.getCameraLocationAtPartHeight(firstPart, camera, first, firstAngle).convertToUnits(LengthUnit.Millimeters);
        Location b = vision.getCameraLocationAtPartHeight(secondPart, camera, second, secondAngle).convertToUnits(LengthUnit.Millimeters);
        Location ah = first.toHeadLocation(a).convertToUnits(LengthUnit.Millimeters);
        Location bh = second.toHeadLocation(b).convertToUnits(LengthUnit.Millimeters);
        if (bh.getX() - ah.getX() < 8 || bh.getX() - ah.getX() > 45 || Math.abs(bh.getY() - ah.getY()) > 4)
            { throw new Exception("两头顺序或间距不适合连续 +X 飞拍"); }
        start = a.derive(a.getX() - 4, null, null, null);
        destinations = Arrays.asList(a.derive(a.getX() - 1.2, null, null, null),
                a.derive(a.getX() + 1.2, null, null, null),
                b.derive(b.getX() - 1.2, null, null, null),
                b.derive(b.getX() + 1.2, null, null, null),
                b.derive(b.getX() + 6, null, second.getEffectiveSafeZ().convertToUnits(b.getUnits()).getValue(), null));
        tools = Arrays.asList(first, first, second, second, second);
        live = SafetyWorkbenchStore.get();
        revision = live.getRevision();
        com.google.gson.Gson gson = new com.google.gson.Gson();
        frozen = gson.fromJson(gson.toJson(live), SafetyWorkbenchModel.class);
        preview = new ArrayList<>();
        AxesLocation previous = new AxesLocation(machine).put(raw(first, start));
        // Both rotations are pre-positioned before the route starts.
        previous = previous.put(raw(second, b).byType(Axis.Type.Rotation));
        for (int i = 0; i < destinations.size(); i++) {
            if (!tools.get(i).isReachable(destinations.get(i))) { throw new Exception("连续飞拍目标超出限位"); }
            AxesLocation next = previous.put(raw(tools.get(i), destinations.get(i)));
            preview.add(new Motion(tools.get(i), previous, next,
                    machine.getSpeed() * first.getHead().getMaxPartSpeed(), Motion.MotionOption.UncoordinatedMotion));
            previous = next;
        }
        ContinuousVisionProfile.solve(preview, velocity);
        validate(preview);
    }

    private static AxesLocation raw(Nozzle nozzle, Location location) throws Exception {
        return nozzle.toRaw(nozzle.toHeadLocation(location, LocationOption.Quiet), LocationOption.Quiet);
    }

    public void validate(List<Motion> motions) throws Exception {
        if (SafetyWorkbenchStore.get() != live || !live.isCertified() || live.getRevision() != revision)
            { throw new Exception("连续飞拍包络认证已改变"); }
        List<AxesLocation> samples = new ArrayList<>();
        for (Motion motion : motions) {
            int intervals = Math.max(2, (int)Math.ceil(motion.getTime() / .005));
            for (int i = 0; i <= intervals; i++)
                { samples.add(motion.getMomentaryLocation(motion.getTime() * i / intervals)); }
        }
        var result = DynamicSafetyCollisionEngine.validateRawTransit(frozen, first.getHead().getMachine(), first, samples);
        if (!result.isSafe()) { throw new Exception("连续飞拍包络不通过：" + result.getIssues()); }
    }

    /** Caller positions the first entry and both rotations before opening the camera stream. */
    public void execute() throws Exception {
        if (!new AxesLocation(first.getHead().getMachine()).matches(preview.get(0).getLocation0()))
            { throw new Exception("连续飞拍实际起点与预览不同"); }
        try (ContinuousVisionProfile scope = new ContinuousVisionProfile(preview, velocity, this::validate)) {
            for (int i = 0; i < tools.size(); i++)
                { tools.get(i).moveTo(destinations.get(i), first.getHead().getMachine().getSpeed(), Motion.MotionOption.UncoordinatedMotion); }
            second.waitForCompletion(MotionPlanner.CompletionType.WaitForStillstand);
        }
    }
}
