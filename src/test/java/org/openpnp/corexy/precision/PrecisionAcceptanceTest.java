package org.openpnp.corexy.precision;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PrecisionAcceptanceTest {
    private PrecisionMetrics metrics(double rms, double p95, double maximum) {
        return new com.google.gson.Gson().fromJson("{\"count\":34,\"rms\":" + rms
                + ",\"p95\":" + p95 + ",\"maximum\":" + maximum + "}", PrecisionMetrics.class);
    }

    @Test
    void bothRecordedOctober17ChecksImproveOverTheUncompensatedBaseline() {
        PrecisionMetrics baseline = metrics(.11648616052364387, .17594669731785925, .18845106646175025);
        assertTrue(PrecisionAcceptance.reasons(baseline,
                metrics(.03231853073144884, .06420794986607087, .06497714773384745), .00295769973278915).isEmpty());
        assertTrue(PrecisionAcceptance.reasons(baseline,
                metrics(.029808927242700893, .0512463933367006, .056073670557681976), .00295769973278915).isEmpty());
    }

    @Test
    void smallButResolvableImprovementDoesNotNeedEightPercentOrAnAbsoluteGoal() {
        assertTrue(PrecisionAcceptance.reasons(metrics(.1, .1, .12), metrics(.096, .096, .12), .001).isEmpty());
    }

    @Test
    void noiseIndistinguishableImprovementAndWorseMaximumHaveConcreteReasons() {
        String reasons = String.join("\n", PrecisionAcceptance.reasons(
                metrics(.1, .1, .12), metrics(.101, .099, .14), .001));
        assertTrue(reasons.contains("P95 改善不足"));
        assertTrue(reasons.contains("RMS 未降低"));
        assertTrue(reasons.contains("最大误差退化"));
        assertTrue(reasons.contains("0.12200"));
    }

    @Test
    void missingOrNonFiniteBaselineIsNotAccepted() {
        assertFalse(PrecisionAcceptance.reasons(null, metrics(.01, .02, .03), .001).isEmpty());
        assertFalse(PrecisionAcceptance.reasons(metrics(.1, Double.NaN, .2), metrics(.01, .02, .03), .001).isEmpty());
    }
}
