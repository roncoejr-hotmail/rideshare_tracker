package org.miosys.transport.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import org.miosys.transport.auth.Door;
import org.miosys.transport.auth.DoorResult;
import org.miosys.transport.auth.RequestContext;

/**
 * Single handler: open routes bypass the door, /api/ routes go through it, anything else
 * falls through to 404/405. The only place a principal is ever created or bound.
 */
public final class ApiHandler implements HttpHandler {

    private static final System.Logger LOG = System.getLogger("transport");

    private final Router router;
    private final Door door;
    private final java.util.function.Function<HttpExchange, RawTarget> targetFn;
    private final Cors cors;

    public ApiHandler(Router router, Door door) {
        this(router, door, ApiHandler::defaultTarget, Cors.disabled());
    }

    public ApiHandler(Router router, Door door, Cors cors) {
        this(router, door, ApiHandler::defaultTarget, cors);
    }

    /** Package-visible: tests supply the raw target to drive the handler without sockets. */
    ApiHandler(
            Router router,
            Door door,
            java.util.function.Function<HttpExchange, RawTarget> targetFn) {
        this(router, door, targetFn, Cors.disabled());
    }

    ApiHandler(
            Router router,
            Door door,
            java.util.function.Function<HttpExchange, RawTarget> targetFn,
            Cors cors) {
        this.router = router;
        this.door = door;
        this.targetFn = targetFn;
        this.cors = cors;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // Not try-with-resources: the resource would close BEFORE the catch blocks
        // run, and error responses must be sent on an open exchange.
        try {
            RawTarget target = targetFn.apply(ex);
            String method = ex.getRequestMethod();
            String origin = ex.getRequestHeaders().getFirst("Origin");
            boolean cors = this.cors.isAllowed(origin) && isApi(target.rawPath());
            if (cors) {
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                ex.getResponseHeaders().set("Vary", "Origin");
            }
            if ("OPTIONS".equals(method)
                    && cors
                    && ex.getRequestHeaders().getFirst("Access-Control-Request-Method") != null) {
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", Cors.ALLOW_METHODS);
                ex.getResponseHeaders().set("Access-Control-Allow-Headers", Cors.ALLOW_HEADERS);
                ex.getResponseHeaders().set("Access-Control-Max-Age", Cors.MAX_AGE);
                Json.sendEmpty(ex, 200);
                return;
            }
            if (isOpen(method, target.rawPath())) {
                router.route(ex, PathCodec.segments(target.rawPath()), target.rawQuery());
                return;
            }
            if (isApi(target.rawPath())) {
                DoorResult result;
                try {
                    result = door.open(ex.getRequestHeaders().getFirst("Authorization"));
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                switch (result) {
                    case DoorResult.Allowed(var p) -> {
                        List<String> segments = PathCodec.segments(target.rawPath());
                        String query = target.rawQuery();
                        try {
                            ScopedValue.where(
                                            RequestContext.PRINCIPAL,
                                            p)
                                    .call(
                                            () -> {
                                                router.route(ex, segments, query);
                                                return null;
                                            });
                        } catch (RuntimeException | IOException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                    case DoorResult.Rejected _ ->
                        Json.send(ex, 401, Json.error("unauthorized - missing or invalid API key"));
                }
                return;
            }
            router.route(ex, PathCodec.segments(target.rawPath()), target.rawQuery());
        } catch (ApiException e) {
            if (e instanceof ApiException.MethodNotAllowed m) {
                ex.getResponseHeaders().set("Allow", m.allow());
            }
            Json.send(ex, e.status(), Json.error(e.getMessage()));
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "unexpected error", e);
            Json.send(ex, 500, Json.error("internal error"));
        } finally {
            ex.close();
        }
    }

    /** Raw request target. */
    record RawTarget(String rawPath, String rawQuery) {}

    private static RawTarget defaultTarget(HttpExchange ex) {
        var uri = ex.getRequestURI();
        return new RawTarget(uri.getRawPath(), uri.getRawQuery());
    }

    private static boolean isOpen(String method, String rawPath) {
        String path = stripTrailingSlash(rawPath);
        return ("GET".equals(method) && "/health".equals(path))
                || ("POST".equals(method) && "/api/bootstrap".equals(path));
    }

    private static boolean isApi(String rawPath) {
        return "/api".equals(rawPath) || rawPath.startsWith("/api/");
    }

    private static String stripTrailingSlash(String rawPath) {
        int end = rawPath.length();
        while (end > 1 && rawPath.charAt(end - 1) == '/') {
            end--;
        }
        return rawPath.substring(0, end);
    }
}
