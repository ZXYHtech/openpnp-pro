package org.openpnp.corexy.agent;

import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Motion.MotionOption;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.MotionPlanner.CompletionType;

import net.bytebuddy.asm.Advice;

final class CoreXYAdvice {
    static final class ToolForward {
        @Advice.OnMethodEnter
        static void enter(@Advice.This org.openpnp.machine.reference.ReferenceNozzle nozzle,
                @Advice.Argument(value = 0, readOnly = false) org.openpnp.model.Location location,
                @Advice.Argument(2) org.openpnp.spi.Locatable.LocationOption[] options) {
            if (!java.util.Arrays.asList(options).contains(org.openpnp.spi.Locatable.LocationOption.SuppressDynamicCompensation)) {
                double angle = location.getRotation() - (nozzle.getRotationModeOffset() == null ? 0 : nozzle.getRotationModeOffset());
                location = location.subtract(org.openpnp.corexy.precision.ToolPointRuntime.correction(nozzle,
                        location.derive(null, null, null, angle)));
            }
        }
    }
    static final class ToolInverse {
        @Advice.OnMethodExit
        static void exit(@Advice.This org.openpnp.machine.reference.ReferenceNozzle nozzle,
                @Advice.Argument(2) org.openpnp.spi.Locatable.LocationOption[] options,
                @Advice.Return(readOnly = false) org.openpnp.model.Location location) {
            if (!java.util.Arrays.asList(options).contains(org.openpnp.spi.Locatable.LocationOption.SuppressDynamicCompensation)) {
                double angle = location.getRotation() - (nozzle.getRotationModeOffset() == null ? 0 : nozzle.getRotationModeOffset());
                location = location.add(org.openpnp.corexy.precision.ToolPointRuntime.correction(nozzle,
                        location.derive(null, null, null, angle)));
            }
        }
    }
    static final class ToolCamera {
        @Advice.OnMethodExit
        static void exit(@Advice.This org.openpnp.machine.reference.ReferenceNozzle nozzle,
                @Advice.Return(readOnly = false) org.openpnp.model.Location result) {
            if (org.openpnp.corexy.precision.ToolPointRuntime.replacesNative(nozzle)) {
                result = new org.openpnp.model.Location(result.getUnits());
            }
        }
    }
    private CoreXYAdvice() {
    }

    static final class Plan {
        @Advice.OnMethodEnter
        static CoreXYRuntime.PlanContext enter(
                @Advice.This AbstractMotionPlanner planner,
                @Advice.Argument(0) HeadMountable hm,
                @Advice.Argument(1) double speed,
                @Advice.Argument(value = 2, readOnly = false) AxesLocation current,
                @Advice.Argument(value = 3, readOnly = false) AxesLocation target) {
            CoreXYRuntime.PlanContext context = CoreXYRuntime.beforePlan(
                    planner, hm, speed, current, target);
            CoreXYRuntime.beginTransaction(planner, context);
            current = context.physicalCurrent;
            target = context.physicalTarget;
            return context;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(
                @Advice.This AbstractMotionPlanner planner,
                @Advice.Argument(0) HeadMountable hm,
                @Advice.Argument(1) double speed,
                @Advice.Argument(4) MotionOption[] options,
                @Advice.Enter CoreXYRuntime.PlanContext context,
                @Advice.Thrown(readOnly = false) Throwable failure,
                @Advice.Return(readOnly = false) AxesLocation result) {
            if (failure == null) {
                try {
                    result = CoreXYRuntime.afterPlan(planner, hm, speed, options, result, context);
                }
                catch (Throwable rejected) {
                    failure = rejected;
                }
            }
            if (failure != null) {
                CoreXYRuntime.rollbackTransaction(planner, context);
            }
        }
    }

    static final class SetGlobalOffsets {
        @Advice.OnMethodEnter
        static AxesLocation enter(
                @Advice.This AbstractMotionPlanner planner,
                @Advice.Argument(value = 0, readOnly = false) AxesLocation location) {
            AxesLocation logical = location;
            location = CoreXYRuntime.adjustGlobalOffsets(planner, logical);
            return logical;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@Advice.Enter AxesLocation logical) {
            CoreXYRuntime.restoreGlobalOffsetCoordinates(logical);
        }
    }

    static final class WaitForCompletion {
        @Advice.OnMethodExit
        static void exit(
                @Advice.This AbstractMotionPlanner planner,
                @Advice.Argument(1) CompletionType completionType) {
            if (completionType.isEnforcingStillstand()) {
                CoreXYRuntime.correctReportedCoordinates(planner);
            }
        }
    }

    static final class Home {
        @Advice.OnMethodEnter
        static void enter(@Advice.This AbstractMotionPlanner planner) {
            CoreXYRuntime.beginHome(planner);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@Advice.This AbstractMotionPlanner planner) {
            CoreXYRuntime.endHome(planner);
        }
    }

    static final class MachineHome {
        @Advice.OnMethodEnter
        static void enter(@Advice.This ReferenceMachine machine) {
            CoreXYRuntime.beginHome(
                    (AbstractMotionPlanner) machine.getMotionPlanner());
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@Advice.This ReferenceMachine machine) {
            CoreXYRuntime.endHome(
                    (AbstractMotionPlanner) machine.getMotionPlanner());
        }
    }

    static final class Unhome {
        @Advice.OnMethodEnter
        static void enter(@Advice.This AbstractMotionPlanner planner) {
            CoreXYRuntime.reset(planner, "unhome");
        }
    }

    static final class MachineHomed {
        @Advice.OnMethodEnter
        static void enter(@Advice.This ReferenceMachine machine,
                @Advice.Argument(0) boolean homed) throws Exception {
            if (homed && machine.getMotionPlanner() instanceof AbstractMotionPlanner) {
                CoreXYRuntime.beforeMachineHomed((AbstractMotionPlanner) machine.getMotionPlanner());
            }
        }
    }
}
