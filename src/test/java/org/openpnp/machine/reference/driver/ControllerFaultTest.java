package org.openpnp.machine.reference.driver;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.Configuration;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.util.GcodeServer;

class ControllerFaultTest {
    @TempDir Path temporary;
    @BeforeEach void setup() throws Exception {
        Configuration.initialize(temporary.toFile());
        ReferenceMachine machine = new ReferenceMachine();
        machine.addHead(new org.openpnp.machine.reference.ReferenceHead());
        Configuration.get().setMachine(machine);
    }
    static class Driver extends GcodeDriver {
        void response(String response) { processResponse(new Line(response));
        }
        void fault() throws Exception { checkControllerFault();
        }
        void waitAck() throws Exception { waitForConfirmation("G28", 10000); }
    }

    @Test void missingErrorRegexStillDetectsHaltAndPreservesRawResponse() throws Exception {
        Driver driver = new Driver();
        driver.createDefaults();
        driver.response("!!");
        Exception fault = assertThrows(ControllerFaultException.class, driver::fault);
        assertTrue(fault.getMessage().contains("原始返回：!!"));
        assertTrue(fault.getMessage().contains("不会自动解锁"));
        driver.response("ok");
        assertThrows(ControllerFaultException.class, driver::fault);
    }

    @Test void errorTakesPriorityOverBroadAcknowledgementAndWakesWaitingThread() throws Exception {
        Driver driver = new Driver();
        driver.createDefaults();
        driver.setCommand(null, GcodeDriver.CommandType.COMMAND_CONFIRM_REGEX, ".*");
        Thread responder = new Thread(() -> {
            try { Thread.sleep(30);
            } catch (InterruptedException ignored) { }
            driver.response("ERROR: Homing cycle failed - check the max_travel settings");
        });
        responder.start();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Exception fault = assertThrows(ControllerFaultException.class, driver::waitAck);
            assertTrue(fault.getMessage().contains("max_travel"));
        });
        responder.join(3000);
        assertTrue(driver.receivedConfirmationsQueue.size() <= 1);
    }

    @Test void configuredFaultAndAlarmAreRecognizedButStatusTextIsNot() throws Exception {
        for (String error : new String[] {"ALARM:1", "error: bad command", "CUSTOM FAULT"}) {
            Driver driver = new Driver();
            driver.createDefaults();
            driver.setCommand(null, GcodeDriver.CommandType.COMMAND_ERROR_REGEX, "CUSTOM FAULT");
            driver.response("FIRMWARE_NAME:Test ERROR_COUNT:0");
            driver.fault();
            driver.response(error);
            assertThrows(ControllerFaultException.class, driver::fault);
        }
    }

    @Test void syncAndAsyncDriverStopOnRejectedHomeAndAllowExplicitReconnect() throws Exception {
        for (boolean async : new boolean[] {false, true}) {
            Configuration.initialize(temporary.resolve("machine-" + async).toFile());
            ReferenceMachine machine = new ReferenceMachine();
            machine.addHead(new org.openpnp.machine.reference.ReferenceHead());
            Configuration.get().setMachine(machine);
            GcodeServer server = new GcodeServer();
            GcodeDriver driver = async ? new GcodeAsyncDriver() : new GcodeDriver();
            try {
                driver.createDefaults();
                driver.setConnectWaitTimeMilliseconds(0);
                driver.setCommunicationsType(AbstractReferenceDriver.CommunicationsType.tcp);
                TcpCommunications tcp = (TcpCommunications) driver.getCommunications();
                tcp.setIpAddress("localhost");
                tcp.setPort(server.getListenerPort());
                server.addCommandResponse("G21", "ok");
                server.addCommandResponse("G90", "ok");
                server.addCommandResponse("G28", "!!");
                server.addCommandResponse("M400", "ok");
                server.addCommandResponse("M114", "X:0 Y:0 Z:0\nok");
                driver.setCommand(null, GcodeDriver.CommandType.CONNECT_COMMAND, "G21\nG90");
                driver.setCommand(null, GcodeDriver.CommandType.HOME_COMMAND, "G28");
                driver.setCommand(null, GcodeDriver.CommandType.MOVE_TO_COMPLETE_COMMAND, "M400");
                driver.setCommand(null, GcodeDriver.CommandType.GET_POSITION_COMMAND, "M114");
                driver.setCommand(null, GcodeDriver.CommandType.POSITION_REPORT_REGEX, "^X:.*");
                machine.addDriver(driver);
                driver.connect();
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                    assertThrows(ControllerFaultException.class, () -> {
                        driver.home(machine);
                        driver.waitForCompletion(null, CompletionType.WaitForStillstandIndefinitely);
                    });
                });
                assertThrows(ControllerFaultException.class, () -> driver.sendCommand("G0 X100"));
                driver.disconnect();
                server.addCommandResponse("G28", "ok");
                driver.connect();
                driver.sendCommand("G28");
                driver.waitForCompletion(null, CompletionType.WaitForStillstandIndefinitely);
            }
            finally {
                driver.disconnect();
                server.shutdown();
            }
        }
    }

    @Test void failedHomeCannotLeaveMachineMarkedHomed() throws Exception {
        ReferenceMachine machine = (ReferenceMachine) Configuration.get().getMachine();
        org.openpnp.spi.MotionPlanner planner = org.mockito.Mockito.mock(org.openpnp.spi.MotionPlanner.class);
        machine.setMotionPlanner(planner);
        machine.setHomed(true);
        ControllerFaultException failure = new ControllerFaultException("COM3", "!!");
        org.mockito.Mockito.doThrow(failure).when(planner).waitForCompletion(null, CompletionType.WaitForStillstand);
        assertSame(failure, assertThrows(ControllerFaultException.class, machine::home));
        assertFalse(machine.isHomed());
        org.mockito.Mockito.verify(planner).unhome();
    }
}
