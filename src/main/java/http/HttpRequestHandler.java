package http;

import java.io.IOException;

/**
 * Handles a single HTTP request.
 * Implementations must be thread-safe as requests are handled concurrently.
 */
public interface HttpRequestHandler {
    HttpResponse handle(HttpRequest request) throws IOException;
}
