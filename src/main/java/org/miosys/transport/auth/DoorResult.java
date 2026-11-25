package org.miosys.transport.auth;

/** Door outcome. Rejections never reveal which check failed. */
public sealed interface DoorResult permits DoorResult.Allowed, DoorResult.Rejected {

    record Allowed(Principal principal) implements DoorResult {}

    enum Rejected implements DoorResult {
        INSTANCE
    }
}
