package org.openpnp.corexy.precision;

/** A captured frame cannot provide an unambiguous, complete marker measurement. */
public final class MarkerDetectionException extends IllegalStateException {
    public MarkerDetectionException(String message) {
        super(message);
    }

    public MarkerDetectionException(String message, Throwable cause) {
        super(message, cause);
    }
}