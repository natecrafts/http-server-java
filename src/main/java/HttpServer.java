import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class HttpServer {
    final int PORT = 4221;

    public void run() {
        System.out.println("Starting HTTP server...");

        // Create a "listening socket" which accepts new connections.
        // This is initially an unbound socket, i.e. it is not yet bound to a port.
        try (ServerSocket listeningSocket = new ServerSocket()) {
            // Instruct the kernel to allow reusing a port lingering in the TIME_WAIT
            // state after it was recently closed.
            // Since the tester restarts your program quite often, setting SO_REUSEADDR
            // ensures that we don't run into 'Address already in use' errors.
            listeningSocket.setReuseAddress(true);

            // Bind the listening socket to a port.
            listeningSocket.bind(new InetSocketAddress(PORT));

            // Wait for connection from client.
            // For each connection, creates a new "connection socket" that
            // does the actual communication.
            try (Socket connectionSocket = listeningSocket.accept()) {
                System.out.println("Accepted new connection");
                InputStream in = connectionSocket.getInputStream();
                OutputStream out = connectionSocket.getOutputStream();
                handleRequest(in, out);
            }
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }

    static void handleRequest(InputStream in, OutputStream out) throws IOException {
        HttpRequest request;
        try {
            request = parseRequest(in);
        } catch (HttpException e) {
            // TODO: Send an appropriate response here.
            throw new RuntimeException(e);
        }
        if (request.path == null) {
            sendResponse(out, HttpStatus.NOT_FOUND, null, null);
        } else if (request.path.equals("/")) {
            sendResponse(out, HttpStatus.OK, null, null);
        } else if (request.path.startsWith("/echo/")) {
            String str = request.path.substring("/echo/".length());
            String contentType = "text/plain";
            // According to RFC 6657, we use the US_ASCII charset for content type
            // text/plain by default, unless the content type header also explicitly
            // includes a charset.
            // For example: 'Content-Type: text/plain; charset=utf-8'
            byte[] body = str.getBytes(StandardCharsets.US_ASCII);
            sendResponse(out, HttpStatus.OK, contentType, body);
        } else {
            sendResponse(out, HttpStatus.NOT_FOUND, null, null);
        }
    }

    static void sendResponse(OutputStream out, HttpStatus status, String contentType, byte[] body)
            throws IOException {
        // Use a byte buffer to minimize the number of syscalls taken to send the response.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        StringBuilder header = new StringBuilder();
        // Response line
        header.append("HTTP/1.1 ");
        header.append(status.code).append(" ").append(status.reason);
        header.append("\r\n");

        // Header lines
        if (contentType != null) {
            header.append("Content-Type: ").append(contentType).append("\r\n");
        }
        if (body != null) {
            header.append("Content-Length: ").append(body.length).append("\r\n");
        }

        header.append("\r\n");
        // Use the US_ASCII charset to encode the header to comply with RFC 9110 and RFC 9112.
        buf.write(header.toString().getBytes(StandardCharsets.US_ASCII));

        if (body != null) {
            buf.write(body);
        }

        out.write(buf.toByteArray());
        out.flush();
    }

    static String parseRequestHeaderLine(InputStream in) throws IOException, HttpException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') {
                    break;
                } else {
                    throw new HttpException("Illegal bare CR.");
                }
            } else if (b == '\n') {
                // RFC 9112 allows bare LF section 2.2.
                break;
            }
            buf.write(b);
        }
        // Use US_ASCII to comply with RFC 9112.
        return buf.toString(StandardCharsets.US_ASCII);
    }

    static HttpRequest parseRequest(InputStream in) throws IOException, HttpException {
        String requestLine = parseRequestHeaderLine(in);
        String[] requestLineParts = requestLine.split(" ");
        if (requestLineParts.length != 3) {
            throw new HttpException("Malformed request line");
        }
        String method = requestLineParts[0];
        String path = requestLineParts[1];
        String version = requestLineParts[2];

        Map<String, String> headers = new HashMap<>();
        while (true) {
            String headerLine = parseRequestHeaderLine(in);
            if (headerLine.isEmpty()) {
                break;
            }
        }

        return new HttpRequest(method, path, version, headers);
    }

    static class HttpStatus {
        static final HttpStatus OK = new HttpStatus(200, "OK");
        static final HttpStatus NOT_FOUND = new HttpStatus(404, "Not Found");

        private final int code;
        private final String reason;

        HttpStatus(int code, String reason) {
            this.code = code;
            this.reason = reason;
        }
    }

    static class HttpException extends Exception {
        public final String message;

        HttpException(String message) {
            this.message = message;
        }
    }

    static class HttpRequest {
        String method;
        String path;
        String version;
        Map<String, String> headers;

        HttpRequest(String method, String path, String version, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.version = version;
            this.headers = headers;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof HttpRequest other)) return false;
            return Objects.equals(method, other.method)
                    && Objects.equals(path, other.path)
                    && Objects.equals(version, other.version)
                    && Objects.equals(headers, other.headers);
        }

        @Override
        public int hashCode() {
            return Objects.hash(method, path, version, headers);
        }
    }
}
