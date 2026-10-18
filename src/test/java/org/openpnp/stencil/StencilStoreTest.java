package org.openpnp.stencil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;

class StencilStoreTest {

    @Test
    void paperDefaultsPersistApartFromModel() throws Exception {
        StencilStore store = new StencilStore(dir);
        assertArrayEquals(new double[] { 50, 30 }, store.paper());
        store.paper(80, 40);
        assertArrayEquals(new double[] { 80, 40 }, new StencilStore(dir).paper());
        assertEquals(StencilParameters.factory().json().toString(), store.startup().json().toString());
        assertThrows(java.io.IOException.class, () -> store.paper(10, 20));
    }

    @TempDir
    Path dir;

    @Test
    void explicitReplaceKeepsIdBackupAndRejectsStaleRevision() throws Exception {
        StencilStore s = new StencilStore(dir);
        StencilParameters p = StencilParameters.factory();
        StencilStore.Preset a = s.save("夹具 A", p, null, 0);
        assertThrows(StencilStore.ReplaceRequired.class, () -> s.save("夹具 A", p, null, 0));
        StencilStore.Preset b = s.save("夹具 A", p.with("model", "新型号"), a.id, a.revision);
        assertEquals(a.id, b.id);
        assertEquals(2, b.revision);
        assertThrows(java.io.IOException.class, () -> s.save("夹具 A", p, a.id, a.revision));
        assertTrue(Files.exists(dir.resolve("backups").resolve(a.id + "-1.json")));
        assertEquals("新型号", new StencilStore(dir).presets(false).get(0).parameters.s("model"));
    }

    @Test
    void deleteUndoCollisionAndStartupAreIndependent() throws Exception {
        StencilStore s = new StencilStore(dir);
        StencilParameters p = StencilParameters.factory();
        StencilStore.Preset a = s.save("A", p, null, 0);
        s.deleted(a.id, true);
        assertTrue(s.presets(false).isEmpty());
        s.deleted(a.id, false);
        assertEquals(1, s.presets(false).size());
        s.deleted(a.id, true);
        s.save("A", p, null, 0);
        assertThrows(java.io.IOException.class, () -> s.deleted(a.id, false));
        s.startup(p.with("model", "启动"));
        assertEquals("启动", new StencilStore(dir).startup().s("model"));
        assertEquals(p.s("model"), s.presets(false).get(0).parameters.s("model"));
    }

    @Test
    void outputNamesCannotEscapeOrUseWindowsDevices() {
        for (String name : new String[] { "../../CON", "A/B:C", "NUL", "..", "COM1", "", "夹具" }) {
            String safe = StencilFiles.safeName(name);
            assertFalse(safe.contains("/"));
            assertFalse(safe.contains("\\"));
            assertTrue(dir.resolve(safe).normalize().startsWith(dir));
            assertTrue(safe.startsWith("型号_"));
        }
    }
}
