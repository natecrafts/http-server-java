import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class CodeCraftersHandler implements HttpHandler {
    private final Path directory;

    public CodeCraftersHandler(Path directory) {
        this.directory = directory;
    }

    public CodeCraftersHandler() {
        this(null);
    }

    @Override
    public HttpServer.Response handle(HttpServer.Request request) throws IOException {
        HttpServer.HttpStatus status;
        String contentType = null;
        byte[] body = null;

        if (request.path == null) {
            // NOTE: When the status is NOT_FOUND, we normally do not close the connection
            // unless the request has the 'Connection: close' header.
            status = HttpServer.HttpStatus.NOT_FOUND;
        } else if (request.path.equals("/")) {
            status = HttpServer.HttpStatus.OK;
        } else if (request.path.startsWith("/echo/")) {
            String str = request.path.substring("/echo/".length());
            status = HttpServer.HttpStatus.OK;
            contentType = "text/plain";
            // According to RFC 6657, we use the US_ASCII charset for content type
            // text/plain by default, unless the content type header also explicitly
            // includes a charset.
            // For example: 'Content-Type: text/plain; charset=utf-8'
            body = str.getBytes(StandardCharsets.US_ASCII);
        } else if (request.path.startsWith("/user-agent")) {
            status = HttpServer.HttpStatus.OK;
            String userAgent = request.headers.get("user-agent");
            contentType = "text/plain";
            body = userAgent.getBytes(StandardCharsets.US_ASCII);
        } else if (request.path.startsWith("/files/")) {
            String fileName = request.path.substring("/files/".length());
            if (request.method.equals("GET")) {
                // We normalize and check if the filePath actually starts with directory
                // to make sure there is no path traversal. If there is an issue, return
                // NOT_FOUND.
                Path filePath;
                if (directory == null) {
                    filePath = null;
                } else {
                    filePath = directory.resolve(fileName).normalize();
                }
                if (filePath == null
                        || !filePath.startsWith(directory)
                        || !Files.isRegularFile(filePath)) {
                    status = HttpServer.HttpStatus.NOT_FOUND;
                } else {
                    status = HttpServer.HttpStatus.OK;
                    contentType = "application/octet-stream";
                    body = Files.readAllBytes(filePath);
                }
            } else if (request.method.equals("POST")) {
                // We normalize and check if the filePath actually starts with directory
                // to make sure there is no path traversal. If there is an issue, return
                // BAD_REQUEST instead of NOT_FOUND.
                Path filePath;
                if (directory == null) {
                    filePath = null;
                } else {
                    filePath = directory.resolve(fileName).normalize();
                }
                if (request.body == null
                        || filePath == null
                        || !filePath.startsWith(directory)
                        || fileName.isEmpty()) {
                    status = HttpServer.HttpStatus.BAD_REQUEST;
                    // closingConnection = true;
                } else {
                    Files.write(filePath, request.body);
                    status = HttpServer.HttpStatus.CREATED;
                }
            } else {
                status = HttpServer.HttpStatus.METHOD_NOT_ALLOWED;
            }
        } else {
            // NOTE: When the status is NOT_FOUND, we normally do not close the connection
            // unless the request has the 'Connection: close' header.
            status = HttpServer.HttpStatus.NOT_FOUND;
        }
        return new HttpServer.Response(status, contentType, body);
    }
}
