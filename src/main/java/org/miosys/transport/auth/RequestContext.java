package org.miosys.transport.auth;

/** Carries the request's principal. Stores read the tenant id only from here. */
public final class RequestContext {

    public static final ScopedValue<Principal> PRINCIPAL = ScopedValue.newInstance();

    private RequestContext() {}

    public static Principal current() {
        if (!PRINCIPAL.isBound()) {
            throw new IllegalStateException("no principal bound");
        }
        return PRINCIPAL.get();
    }
}
