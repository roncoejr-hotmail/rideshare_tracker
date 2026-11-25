package org.miosys.transport.http;

/** Maps to a status code plus {"error": message}. Anything else is a 500. */
public sealed abstract class ApiException extends RuntimeException
        permits ApiException.BadRequest,
                ApiException.Forbidden,
                ApiException.NotFound,
                ApiException.MethodNotAllowed,
                ApiException.Conflict,
                ApiException.PayloadTooLarge {

    private final int status;

    protected ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static final class BadRequest extends ApiException {
        public BadRequest(String message) {
            super(400, message);
        }
    }

    public static final class Forbidden extends ApiException {
        public Forbidden() {
            super(403, "forbidden");
        }
    }

    public static final class NotFound extends ApiException {
        public NotFound() {
            super(404, "not found");
        }
    }

    public static final class MethodNotAllowed extends ApiException {
        private final String allow;

        public MethodNotAllowed(String allow) {
            super(405, "method not allowed");
            this.allow = allow;
        }

        public String allow() {
            return allow;
        }
    }

    public static final class Conflict extends ApiException {
        public Conflict(String message) {
            super(409, message);
        }
    }

    public static final class PayloadTooLarge extends ApiException {
        public PayloadTooLarge() {
            super(413, "request body too large");
        }
    }
}
