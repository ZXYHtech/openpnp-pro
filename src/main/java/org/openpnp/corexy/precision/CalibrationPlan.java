package org.openpnp.corexy.precision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Independent paths, including subdivided reversals and spatial interpolation holdouts. */
public final class CalibrationPlan {
    public static final class Path {
        public final String id;
        public String markerId;
        public final double x, y, a, b, speed, accelerationFactor;
        public final int signA, signB, pieces;
        public final int settleMillis;
        public boolean homeStart;
        public boolean direct;
        // A fitted path records its actual conditioning distance; NaN uses the requested distance.
        public double conditioningTravel = Double.NaN;

        Path(String id, double x, double y, double a, double b, double speed,
                double accelerationFactor, int signA, int signB, int pieces, int settling) {
            this(id, "", x, y, a, b, speed, accelerationFactor, signA, signB, pieces, settling);
        }

        Path(String id, String markerId, double x, double y, double a, double b, double speed,
                double accelerationFactor, int signA, int signB, int pieces, int settling) {
            this.id = id;
            this.markerId = markerId;
            this.x = x;
            this.y = y;
            this.a = a;
            this.b = b;
            this.speed = speed;
            this.accelerationFactor = accelerationFactor;
            this.signA = signA;
            this.signB = signB;
            this.pieces = pieces;
            this.settleMillis = settling;
        }

        /** Conditioner -> start -> endpoint. No correction movements enter logical history. */
        public double[][] points(double conditioning) {
            if (homeStart || direct) {
                return new double[][] {{x, y}};
            }
            if (Double.isFinite(conditioningTravel)) {
                conditioning = conditioningTravel;
            }
            double dx = (signA * a + signB * b) / 2;
            double dy = (signA * a - signB * b) / 2;
            double sx = x - dx, sy = y - dy;
            double[][] points = new double[pieces + 2][2];
            points[0] = new double[] {sx + (signA + signB) * conditioning / 2,
                    sy + (signA - signB) * conditioning / 2};
            points[1] = new double[] {sx, sy};
            for (int i = 1; i <= pieces; i++) {
                points[i + 1] = new double[] {sx + dx * i / pieces, sy + dy * i / pieces};
            }
            return points;
        }
    }

    public final List<Path> training;
    public final List<Path> validation;
    public final List<Path> finalCheck;

    public CalibrationPlan(List<Path> paths) {
        training = Collections.unmodifiableList(new ArrayList<>(paths));
        validation = Collections.emptyList();
        finalCheck = Collections.emptyList();
    }

    public CalibrationPlan(CalibrationRequest request) {
        request.validate();
        List<Path> train = new ArrayList<>(), validate = new ArrayList<>(), check = new ArrayList<>();
        if (request.fixedMarkers != null) {
            addFixedMarkerPaths(request, train, validate, check);
            training = Collections.unmodifiableList(train);
            validation = Collections.unmodifiableList(validate);
            finalCheck = Collections.unmodifiableList(check);
            return;
        }
        double[] lengths = {1, 3, 6, 12, 24, Math.min(48, request.conditioningTravel)};
        for (int repeat = 0; repeat < request.repeats; repeat++) {
            for (double a : lengths) {
                for (double speed : new double[] {.35, .8}) {
                    for (double acceleration : new double[] {.5, 1}) {
                        addStates(train, "train-state-" + repeat, request.originX, request.originY,
                                a, Math.max(1, a * .75), speed, acceleration, 1, request.settleMillis);
                    }
                }
            }
        }
        if (request.spatial) {
            for (int row = 0; row < request.gridSize; row++) {
                for (int column = 0; column < request.gridSize; column++) {
                    double angle = Math.toRadians(request.plateAngle);
                    double u = column * request.pitch, v = row * request.pitch;
                    double x = request.originX + u * Math.cos(angle) - v * Math.sin(angle);
                    double y = request.originY + u * Math.sin(angle) + v * Math.cos(angle);
                    boolean trainingNode = row % 2 == 0 && column % 2 == 0;
                    List<Path> destination = trainingNode ? train : validate;
                    for (double length : new double[] {5, 19}) {
                        for (double speed : new double[] {.45, .9}) {
                            addStates(destination, (trainingNode ? "train" : "validation") + "-grid",
                                    x, y, length, length * .6, speed, trainingNode ? .7 : 1,
                                    trainingNode ? 1 : 4, request.settleMillis);
                        }
                    }
                }
            }
        }
        for (double distance : new double[] {2, 9, 18, Math.min(36, request.conditioningTravel)}) {
            addStates(validate, "validation-state", request.originX, request.originY,
                    distance, distance * .8, .6, .8, 4, request.settleMillis);
        }
        // These paths are never supplied to model fitting or selection.
        for (int index = 0; index < 2; index++) {
            Path home = new Path("final-home-" + index, request.originX, request.originY,
                    0, 0, index == 0 ? .55 : .7, 1, 0, 0, 1, request.settleMillis);
            home.homeStart = true;
            check.add(home);
        }
        for (double distance : new double[] {4, 11, 21}) {
            addStates(check, "final", request.originX, request.originY,
                    distance, distance * .65, .7, .9, 4, request.settleMillis);
            if (request.spatial) {
                double angle = Math.toRadians(request.plateAngle);
                double u = request.pitch, v = request.pitch * 3;
                addStates(check, "final-grid", request.originX + u * Math.cos(angle) - v * Math.sin(angle),
                        request.originY + u * Math.sin(angle) + v * Math.cos(angle),
                        distance, distance * .65, .55, .8, 1, request.settleMillis);
            }
        }
        training = Collections.unmodifiableList(train);
        validation = Collections.unmodifiableList(validate);
        finalCheck = Collections.unmodifiableList(check);
    }

    private static void addStates(List<Path> paths, String prefix, double x, double y,
            double a, double b, double speed, double acceleration, int pieces, int settle) {
        for (int signA : new int[] {-1, 1}) {
            for (int signB : new int[] {-1, 1}) {
                paths.add(new Path(prefix + "-" + paths.size(), x, y, a, b, speed,
                        acceleration, signA, signB, pieces, settle));
            }
        }
    }

    private static void addFixedMarkerPaths(CalibrationRequest request, List<Path> train,
            List<Path> validate, List<Path> check) {
        FixedMarkerSetup setup = request.fixedMarkers;
        List<FixedMarkerSetup.Marker> otherMarkers = new ArrayList<>(setup.markers);
        otherMarkers.remove(setup.homeIndex);
        int markerIndex = 0;
        for (int repeat = 0; repeat < request.repeats; repeat++) {
            for (double length : new double[] {1, 3, 6, 12, 24, Math.min(48, request.conditioningTravel)}) {
                for (double speed : new double[] {.35, .8}) {
                    for (double acceleration : new double[] {.5, 1}) {
                        FixedMarkerSetup.Marker marker = otherMarkers.get(markerIndex++ % otherMarkers.size());
                        addFixedStates(train, "train-fixed-state-" + repeat, marker,
                                length, Math.max(1, length * .75), speed, acceleration, 1, request.settleMillis);
                    }
                }
            }
        }
        for (FixedMarkerSetup.Marker marker : otherMarkers) {
            for (double length : new double[] {5, 19}) {
                for (double speed : new double[] {.45, .9}) {
                    addFixedStates(train, "train-fixed-position", marker,
                            length, length * .6, speed, .7, 1, request.settleMillis);
                }
            }
            for (double length : new double[] {2, 9, 18, Math.min(36, request.conditioningTravel)}) {
                addFixedStates(validate, "validation-fixed", marker,
                        length, length * .8, .6, .8, 4, request.settleMillis);
            }
            for (double length : new double[] {4, 11, 21}) {
                addFixedStates(check, "final-fixed", marker,
                        length, length * .65, .7, .9, 4, request.settleMillis);
            }
        }
        FixedMarkerSetup.Marker homeMarker = setup.markers.get(setup.homeIndex);
        for (int index = 0; index < 2; index++) {
            Path home = new Path("final-fixed-home-" + index, homeMarker.id,
                    homeMarker.x, homeMarker.y, 0, 0, index == 0 ? .55 : .7,
                    1, 0, 0, 1, request.settleMillis);
            home.homeStart = true;
            check.add(home);
        }
    }

    private static void addFixedStates(List<Path> paths, String prefix, FixedMarkerSetup.Marker marker,
            double a, double b, double speed, double acceleration, int pieces, int settle) {
        for (int signA : new int[] {-1, 1}) {
            for (int signB : new int[] {-1, 1}) {
                paths.add(new Path(prefix + "-" + marker.id + "-" + paths.size(), marker.id,
                        marker.x, marker.y, a, b, speed, acceleration, signA, signB, pieces, settle));
            }
        }
    }

    /** A fresh launch check keeps the current Home and never recursively requests another one. */
    public static List<Path> fixedVerification(FixedMarkerSetup setup, int settleMillis) {
        setup.validate();
        List<Path> paths = new ArrayList<>();
        for (int index = 0; index < setup.markers.size(); index++) {
            FixedMarkerSetup.Marker marker = setup.markers.get(index);
            if (index == setup.homeIndex) {
                for (int repeat = 0; repeat < 2; repeat++) {
                    Path home = new Path("verify-fixed-home-" + repeat, marker.id, marker.x, marker.y,
                            0, 0, .35, 1, 0, 0, 1, settleMillis);
                    home.direct = true;
                    paths.add(home);
                }
            }
            else {
                addFixedStates(paths, "verify-fixed", marker, 7, 5, .6, 1, 3, settleMillis);
            }
        }
        return Collections.unmodifiableList(paths);
    }
}
