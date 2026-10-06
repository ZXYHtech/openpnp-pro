package org.openpnp.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.prefs.Preferences;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FirstUseGuideDialogTest {
    private final Preferences preferences =
            Preferences.userNodeForPackage(FirstUseGuideDialog.class);

    @AfterEach
    void cleanUp() {
        preferences.remove(FirstUseGuideDialog.PREF_COMPLETED);
    }

    @Test
    void guideHasNineStepsAndOnlyAutoShowsUntilCompleted() {
        preferences.remove(FirstUseGuideDialog.PREF_COMPLETED);

        assertEquals(9, FirstUseGuideDialog.getGuidePageCount());
        assertTrue(FirstUseGuideDialog.shouldShow());

        FirstUseGuideDialog.markCompleted();
        assertFalse(FirstUseGuideDialog.shouldShow());
    }
}
