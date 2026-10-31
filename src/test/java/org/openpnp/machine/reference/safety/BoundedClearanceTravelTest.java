package org.openpnp.machine.reference.safety;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.driver.ReferenceAdvancedMotionPlanner;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion;
import org.openpnp.model.MotionProfile;
import org.openpnp.model.AbstractMotionPath;
import org.openpnp.spi.Driver;

class BoundedClearanceTravelTest {
    @TempDir Path temporary;
    DynamicSafeMotionPlannerTest f;

    @BeforeEach void setup() throws Exception {
        f = new DynamicSafeMotionPlannerTest();
        f.temporary = temporary;
        f.fixture();
        var planner = new ReferenceAdvancedMotionPlanner();
        planner.setAllowContinuousMotion(true);
        f.machine.setMotionPlanner(planner);
        assertEquals(Driver.MotionControlType.Full3rdOrderControl, f.driver.getMotionControlType());
        var profile = f.model.findToolProfile(f.head.getId(), f.nozzle.getId());
        profile.zIsNozzleTip = true;
        profile.bodyFollowsNozzleZ = false;
        var object = f.model.createObject(SafetyWorkbenchModel.ObjectType.FEEDER);
        object.x = object.y = 400;
        object.z = -10;
        object.height = 3;
        f.model.addObject(object);
        f.model.certify("Native free-space profile fixture");
        f.z.setCoordinate(-12);
    }

    @Test void nativeSolverProducesABoundedZArchInsteadOfStopsAtPolygonVertices() throws Exception {
        Location start = f.nozzle.getLocation();
        Location target = new Location(LengthUnit.Millimeters, 300, 100, -13, 0);
        var tools = DynamicSafetyCollisionEngine.snapshot(f.model, f.machine, f.nozzle);
        var candidate = BoundedClearanceTravel.plan(f.nozzle, start, target, 1, f.model,
                tools, null, Long.MAX_VALUE, () -> { });
        assertNotNull(candidate);
        assertEquals(3, candidate.locations.size());
        assertEquals(1, candidate.freeIndex);
        assertTrue(candidate.seconds > 0);
        assertTrue(candidate.bounds.minimum.getCoordinate(f.z) < candidate.bounds.maximum.getCoordinate(f.z));
        assertTrue(candidate.locations.get(0).getZ() > start.getZ());
        assertEquals(target, candidate.locations.get(2));
        var motions = new java.util.ArrayList<Motion>();
        AxesLocation previous = new AxesLocation(f.machine);
        double stopped = 0;
        for (int i = 0; i < candidate.locations.size(); i++) {
            Location location = candidate.locations.get(i);
            AxesLocation next = previous.put(f.nozzle.toRaw(f.nozzle.toHeadLocation(location)));
            stopped += new Motion(f.nozzle, previous, next, 1, Motion.MotionOption.CoordinatedMotion).getTime();
            Motion motion = new Motion(f.nozzle, previous, next, 1, i == candidate.freeIndex
                    ? Motion.MotionOption.UncoordinatedMotion : Motion.MotionOption.CoordinatedMotion);
            if (i == candidate.freeIndex) { candidate.bounds.constrain(motion); }
            motions.add(motion);
            previous = next;
        }
        new AbstractMotionPath() {
            @Override public int size() { return motions.size(); }
            @Override public MotionProfile[] get(int index) { return motions.get(index).getAxesProfiles(); }
        }.solve();
        Motion free = motions.get(candidate.freeIndex);
        candidate.bounds.validate(free);
        MotionProfile z = free.getAxesProfiles()[free.getAxisIndex(f.z)];
        assertTrue(z.getVelocity(0) > 0, "Z must keep lifting through the first transition");
        assertTrue(z.getVelocity(7) < 0, "Z must already descend through the last transition");
        assertTrue(z.getHigherSBoundary() > candidate.locations.get(0).getZ() + .1,
                "The actual native profile must form an arch above its endpoint heights");
        assertTrue(candidate.seconds < stopped, "Smoothing must outperform three stop-at-corner moves");
        System.out.printf("NATIVE BOUNDED Z ARCH: stopped=%.6fs smooth=%.6fs entryV=%.6f exitV=%.6f peakZ=%.6f%n",
                stopped, candidate.seconds, z.getVelocity(0), z.getVelocity(7), z.getHigherSBoundary());
        assertTrue(f.commands.isEmpty(), "Planning cannot issue hardware commands");
    }

    @Test void aRestrictedVolumePreventsFreeSpaceSmoothingBeforeMotion() throws Exception {
        var zone = f.model.createObject(SafetyWorkbenchModel.ObjectType.KEEP_OUT);
        zone.x = 140;
        zone.y = 90;
        zone.z = -100;
        zone.width = zone.depth = 20;
        zone.height = 250;
        f.model.addObject(zone);
        f.model.certify("Blocked smoothing volume");
        var candidate = BoundedClearanceTravel.plan(f.nozzle, f.nozzle.getLocation(),
                new Location(LengthUnit.Millimeters, 300, 100, -13, 0), 1, f.model,
                DynamicSafetyCollisionEngine.snapshot(f.model, f.machine, f.nozzle), null, Long.MAX_VALUE, () -> { });
        assertNull(candidate);
        assertTrue(f.commands.isEmpty());
    }

    @Test void changedGeometryGuardPreventsAProfileFromBeingAccepted() throws Exception {
        assertThrows(IllegalStateException.class, () -> BoundedClearanceTravel.plan(f.nozzle, f.nozzle.getLocation(),
                new Location(LengthUnit.Millimeters, 300, 100, -13, 0), 1, f.model,
                DynamicSafetyCollisionEngine.snapshot(f.model, f.machine, f.nozzle), null, Long.MAX_VALUE,
                () -> { throw new IllegalStateException("changed"); }));
        assertTrue(f.commands.isEmpty());
    }

    @Test void queuedRotationDoesNotForceAnExtraLinearStillstandButXyzAndDriverMotionDo() throws Exception {
        class QueueProbe extends ReferenceAdvancedMotionPlanner {
            void queue(Motion motion) { motionCommands.add(motion); }
            void clear() { motionCommands.clear(); }
        }
        QueueProbe probe = new QueueProbe();
        f.machine.setMotionPlanner(probe);
        var rotation = f.axis(org.openpnp.spi.Axis.Type.Rotation, 0);
        AxesLocation start = new AxesLocation(f.machine);
        probe.queue(new Motion(f.nozzle, start, start.put(new AxesLocation(rotation, 90)), 1));
        assertFalse(probe.hasPendingLinearMotion());
        probe.queue(new Motion(f.nozzle, start, start.put(new AxesLocation(f.x, 21)), 1));
        assertTrue(probe.hasPendingLinearMotion());
        probe.clear();
        var pending = org.mockito.Mockito.mock(Driver.class);
        org.mockito.Mockito.when(pending.isMotionPending()).thenReturn(true);
        f.machine.addDriver(pending);
        assertTrue(probe.hasPendingLinearMotion(), "In-flight controller motion still requires completion");
    }

    @Test void nativeDualNozzleCycleScoresAllEightPickVisionPlaceCombinationsWithoutCommands() throws Exception {
        var other = new org.openpnp.machine.reference.ReferenceNozzle();
        other.setAxisX(f.x);
        other.setAxisY(f.y);
        other.setAxisZ(f.axis(org.openpnp.spi.Axis.Type.Z, 20));
        other.setHeadOffsets(new Location(LengthUnit.Millimeters, 20, 0, 0, 0));
        f.head.addNozzle(other);
        var otherZ = (org.openpnp.machine.reference.axis.ReferenceControllerAxis) other.getAxisZ();
        otherZ.setSafeZoneLowEnabled(true);
        otherZ.setSafeZoneLow(new org.openpnp.model.Length(20, LengthUnit.Millimeters));
        f.model.ensureToolProfile(f.head.getId(), other.getId(), "N2");
        SafetyWorkbenchStore.save(f.model);
        var items = new java.util.ArrayList<org.openpnp.spi.PnpJobPlanner.PlannedPlacement>();
        for (var nozzle : java.util.List.of(f.nozzle, other)) {
            var part = new org.openpnp.model.Part("0402");
            part.setHeight(new org.openpnp.model.Length(.5, LengthUnit.Millimeters));
            var placement = new org.openpnp.model.Placement(nozzle.getId());
            placement.setPart(part);
            items.add(new org.openpnp.spi.PnpJobPlanner.PlannedPlacement(nozzle, null,
                    new org.openpnp.spi.PnpJobProcessor.JobPlacement(new org.openpnp.model.BoardLocation(new org.openpnp.model.Board()), placement), null));
        }
        Location[][] targets = {
            { new Location(LengthUnit.Millimeters, 30, 100, -10, 0), new Location(LengthUnit.Millimeters, 300, 100, -10, 0) },
            { new Location(LengthUnit.Millimeters, 200, 200, 5, 0), new Location(LengthUnit.Millimeters, 180, 200, 5, 0) },
            { new Location(LengthUnit.Millimeters, 250, 320, -13, 0), new Location(LengthUnit.Millimeters, 80, 320, -13, 0) }
        };
        var scores = new java.util.ArrayList<org.openpnp.machine.reference.vision.DualNozzleCyclePlanner.Score>();
        for (int mask = 0; mask < 8; mask++) {
            scores.add(org.openpnp.machine.reference.vision.DualNozzleCyclePlanner.evaluate(items, targets,
                    new boolean[2], 0, mask, new AxesLocation(f.machine)));
        }
        var best = org.openpnp.machine.reference.vision.DualNozzleCyclePlanner.best(scores);
        assertNotNull(best);
        double minimum = scores.stream().mapToDouble(s -> s.seconds).min().orElseThrow();
        assertTrue(best.seconds <= minimum + .005);
        assertTrue(best.seconds <= scores.get(0).seconds + .005, "An already efficient order need not be changed");
        double worst = scores.stream().mapToDouble(s -> s.seconds).max().orElseThrow();
        assertTrue(best.seconds < worst);
        System.out.printf("DUAL NATIVE CYCLE: worst=%.6fs selected=%.6fs mask=%d XY=%.3fmm reversals=%d%n",
                worst, best.seconds, best.mask, best.distance, best.reversals);
        assertTrue(f.commands.isEmpty());
    }

    @Test void sharedNativeCamKeepsBothNozzlesAboveClearanceThroughoutFreeProfile() throws Exception {
        var ccw = new org.openpnp.machine.reference.axis.ReferenceCamCounterClockwiseAxis();
        ccw.setType(org.openpnp.spi.Axis.Type.Z);
        ccw.setInputAxis(f.z);
        f.machine.addAxis(ccw);
        var cw = new org.openpnp.machine.reference.axis.ReferenceCamClockwiseAxis();
        cw.setType(org.openpnp.spi.Axis.Type.Z);
        cw.setInputAxis(ccw);
        f.machine.addAxis(cw);
        f.nozzle.setAxisZ(ccw);
        f.z.setSafeZoneLow(new org.openpnp.model.Length(0, LengthUnit.Millimeters));
        f.z.setSafeZoneHigh(new org.openpnp.model.Length(0, LengthUnit.Millimeters));
        var other = new org.openpnp.machine.reference.ReferenceNozzle();
        other.setAxisX(f.x);
        other.setAxisY(f.y);
        other.setAxisZ(cw);
        other.setHeadOffsets(new Location(LengthUnit.Millimeters, 20, 0, 0, 0));
        f.head.addNozzle(other);
        var profile = f.model.ensureToolProfile(f.head.getId(), other.getId(), "N2");
        profile.zIsNozzleTip = true;
        profile.bodyFollowsNozzleZ = false;
        f.model.certify("Shared native cam");
        f.z.setCoordinate(-30);
        var candidate = BoundedClearanceTravel.plan(f.nozzle, f.nozzle.getLocation(),
                new Location(LengthUnit.Millimeters, 300, 100, -13, 0), 1, f.model,
                DynamicSafetyCollisionEngine.snapshot(f.model, f.machine, f.nozzle), null, Long.MAX_VALUE, () -> { });
        assertNotNull(candidate);
        double low = candidate.bounds.minimum.getCoordinate(f.z), high = candidate.bounds.maximum.getCoordinate(f.z);
        for (int i = 0; i <= 100; i++) {
            AxesLocation axes = new AxesLocation(f.machine).put(new AxesLocation(f.z, low + (high - low) * i / 100));
            for (var nozzle : java.util.List.of(f.nozzle, other)) {
                assertTrue(nozzle.toHeadMountableLocation(nozzle.toTransformed(axes)).getZ()
                        >= FastTravelClearance.planeZ(f.model) + .49);
            }
        }
        assertTrue(f.commands.isEmpty());
    }
}
