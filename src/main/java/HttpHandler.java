import java.io.IOException;

public interface HttpHandler {
    HttpServer.Response handle(HttpServer.Request request) throws IOException;
}
