package org.openpnp.stencil;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;

class StencilParametersTest {

    @Test
    void sourceFactoryAndEnvelopeRoundtrip() {
        StencilParameters p = StencilParameters.factory();
        assertEquals("YAA2000-GWJJ", p.s("model"));
        assertEquals(146.9, p.n("pcb_length"));
        assertEquals(4, p.n("screw_count"));
        assertEquals(-.1, p.n("pcb_depth_compensation"));
        assertEquals(p.json().toString(), StencilParameters.parse(p.envelope().toString()).json().toString());
    }

    @Test
    void instructionsUseTheProductNameAndCurrentFixtureParameters() {
        String text = new StencilPlan(StencilParameters.factory().with("model", "测试夹具")).instructions();
        assertTrue(text.contains("YAA2000-GWJJ"));
        assertTrue(text.contains("测试夹具"));
        for (String removed : new String[] { "Java", "Python", "原应用", "后台服务", "浏览器" }) {
            assertFalse(text.contains(removed), removed);
        }
        assertDoesNotThrow(() -> new StencilCadTask().fixture(StencilParameters.factory()));
    }

    @Test
    void strictFieldsTypesAndPrecision() {
        String[] bad = { "{\"extra\":1}", "{\"pcb_length\":\"30\"}", "{\"engrave\":1}", "{\"schema_version\":2}", "{\"screw_count\":2.5}", "{\"pcb_depth_compensation\":0.03}", "{\"pivot_compensation\":0.42}", "{\"pcb_length\":null}", "{\"emboss_font\":\"../file\"}", "{\"model\":\"a\\nb\"}" };
        for (String s : bad) {
            assertThrows(RuntimeException.class, () -> StencilParameters.parse(s), s);
        }
    }

    @Test
    void crossFieldGuards() {
        assertThrows(IllegalArgumentException.class, () -> StencilParameters.parse("{\"stencil_margin\":20}"));
        assertThrows(IllegalArgumentException.class, () -> StencilParameters.parse("{\"pcb_length\":15,\"screw_count\":4}"));
        assertDoesNotThrow(() -> StencilParameters.parse("{\"pcb_length\":15,\"screw_count\":4,\"quick_left_extension\":10}"));
    }

    @Test
    void bottomFingerAndDepthDatums() {
        StencilParameters p = StencilParameters.parse("{\"pcb_length\":15,\"pcb_width\":10,\"finger_position\":\"bottom_center\",\"pcb_depth_compensation\":0.2}");
        StencilPlan d = new StencilPlan(p);
        assertEquals(2.5, d.actualFingerRadius);
        assertEquals(d.floor - .2, d.pocketFloor, 1e-9);
        assertNotNull(d.channel);
        assertEquals(d.baseFront - .2, d.channel[1] - d.channel[3] / 2, 1e-9);
        assertTrue(d.pocketFloor - d.reliefDepth >= 1.2 - 1e-9);
    }

    @Test
    void minimumRoofsAcrossSourceParameterRange() {
        for (double t : new double[] { .6, 1.2, 1.6, 3.2 }) {
            for (double dep : new double[] { -.4, 0, .4 }) {
                for (double floor : new double[] { 1.6, 4 }) {
                    JsonObject o = new JsonObject();
                    o.addProperty("pcb_thickness", t);
                    o.addProperty("pcb_depth_compensation", dep);
                    o.addProperty("base_floor", floor);
                    StencilPlan d = new StencilPlan(new StencilParameters(o));
                    assertTrue(d.jawZ >= .6 - 1e-9);
                    assertTrue(d.pocketFloor - d.reliefDepth >= 1.2 - 1e-9);
                    assertEquals(d.hingeY, d.jawFront + 20, 1e-9);
                }
            }
        }
    }

    @Test
    void engravingDoesNotGrowModelOrShrinkText() {
        StencilParameters p = StencilParameters.factory();
        assertEquals(new StencilPlan(p.with("engrave", false)).baseWidth, new StencilPlan(p).baseWidth);
        assertThrows(IllegalArgumentException.class, () -> new StencilCadTask().fixture(p.with("emboss_size", 20)));
        assertTrue(new StencilCadTask().fixture(p.with("engrave", false)).getAsJsonArray("checks").size() > 80);
    }
}
