package org.miosys.transport.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.miosys.transport.auth.RequestContext;
import org.miosys.transport.http.ApiException.Forbidden;
import org.miosys.transport.http.ApiException.MethodNotAllowed;
import org.miosys.transport.http.ApiException.NotFound;

/** Method plus segment patterns to handler lambdas. */
public final class Router {

    @FunctionalInterface
    public interface Handler {
        void handle(HttpExchange ex, Map<String, String> pathParams, Query query) throws IOException;
    }

    public record Route(String method, List<String> pattern, boolean operatorOnly, Handler handler) {}

    private final List<Route> routes = new ArrayList<>();

    public void add(String method, String pattern, Handler handler) {
        add(method, pattern, false, handler);
    }

    public void add(String method, String pattern, boolean operatorOnly, Handler handler) {
        List<String> segments = new ArrayList<>();
        for (String s : pattern.split("/")) {
            if (!s.isEmpty()) {
                segments.add(s);
            }
        }
        routes.add(new Route(method, List.copyOf(segments), operatorOnly, handler));
    }

    public void route(HttpExchange ex, List<String> segments, String rawQuery) throws IOException {
        List<Route> shapeMatches = new ArrayList<>();
        for (Route r : routes) {
            if (match(r.pattern(), segments) != null) {
                shapeMatches.add(r);
            }
        }
        for (Route r : shapeMatches) {
            if (r.method().equals(ex.getRequestMethod())) {
                if (r.operatorOnly() && !RequestContext.current().operator()) {
                    throw new Forbidden();
                }
                Query query = Query.parse(rawQuery);
                r.handler().handle(ex, match(r.pattern(), segments), query);
                return;
            }
        }
        if (!shapeMatches.isEmpty()) {
            String allow =
                    shapeMatches.stream()
                            .map(Route::method)
                            .distinct()
                            .sorted()
                            .collect(Collectors.joining(", "));
            throw new MethodNotAllowed(allow);
        }
        throw new NotFound();
    }

    private static Map<String, String> match(List<String> pattern, List<String> segments) {
        if (pattern.size() != segments.size()) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < pattern.size(); i++) {
            String p = pattern.get(i);
            if (p.startsWith("{") && p.endsWith("}") && p.length() > 2) {
                params.put(p.substring(1, p.length() - 1), segments.get(i));
            } else if (!p.equals(segments.get(i))) {
                return null;
            }
        }
        return params;
    }
}
