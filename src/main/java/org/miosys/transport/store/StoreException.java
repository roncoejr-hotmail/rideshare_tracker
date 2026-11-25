package org.miosys.transport.store;

/** Unchecked wrapper for database failures. Surfaces as a 500. */
public final class StoreException extends RuntimeException {

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public StoreException(Throwable cause) {
        super(cause);
    }
}
