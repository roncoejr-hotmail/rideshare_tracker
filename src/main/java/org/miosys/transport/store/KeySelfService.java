package org.miosys.transport.store;

/** Any principal, own presenting key only. */
public interface KeySelfService {

    /** Replaces the presenting key's hash. Returns the key id. */
    long rotate(String newSecret);
}
