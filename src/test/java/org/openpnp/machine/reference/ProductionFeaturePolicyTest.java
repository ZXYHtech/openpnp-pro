package org.openpnp.machine.reference;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.camera.ImageCamera;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.machine.reference.camera.OpenPnpCaptureCamera;
import org.openpnp.machine.reference.driver.NullDriver;

class ProductionFeaturePolicyTest {
    @Test void productionRejectsSimulationBeforeConnectingAndRetainsRealHardwareChoices() throws Exception {
        String previous = System.getProperty(ProductionFeaturePolicy.HARDWARE_ONLY);
        try {
            System.clearProperty(ProductionFeaturePolicy.HARDWARE_ONLY);
            assertTrue(ProductionFeaturePolicy.hardwareOnly());
            var machine = new ReferenceMachine();
            machine.addDriver(new NullDriver());
            assertThrows(Exception.class, () -> ProductionFeaturePolicy.requireHardware(machine));
            assertThrows(IllegalStateException.class, () -> ProductionFeaturePolicy.requireRealCamera(new ImageCamera()));
            assertThrows(IllegalStateException.class, () -> ProductionFeaturePolicy.requireRealCamera(new SimulatedUpCamera()));
            assertDoesNotThrow(() -> ProductionFeaturePolicy.requireRealCamera(new OpenPnpCaptureCamera()));
            assertFalse(machine.getCompatibleCameraClasses().contains(ImageCamera.class));
            assertFalse(machine.getCompatibleDriverClasses().contains(NullDriver.class));
            System.setProperty(ProductionFeaturePolicy.HARDWARE_ONLY, "false");
            assertDoesNotThrow(() -> ProductionFeaturePolicy.requireHardware(machine));
            assertTrue(machine.getCompatibleCameraClasses().contains(ImageCamera.class));
        }
        finally {
            if (previous == null) { System.clearProperty(ProductionFeaturePolicy.HARDWARE_ONLY); }
            else { System.setProperty(ProductionFeaturePolicy.HARDWARE_ONLY, previous); }
        }
    }
}
