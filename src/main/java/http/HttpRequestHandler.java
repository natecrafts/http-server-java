package http;

import java.io.IOException;

public interface HttpRequestHandler {
    HttpResponse handle(HttpRequest request) throws IOException;
}
