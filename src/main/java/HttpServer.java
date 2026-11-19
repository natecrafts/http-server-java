import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

public class HttpServer {
    final int PORT = 4221;
    final Set<String> SUPPORTED_ENCODINGS = Set.of("gzip");
    private final Path directory;

    public HttpServer() {
        this(null);
    }

    public HttpServer(Path directory) {
        this.directory = directory == null ? null : directory.toAbsolutePath().normalize();
    }

    public void run() {
        System.out.println("Starting HTTP server...");

        // Create a "listening socket" which accepts new connections.
        // This is initially an unbound socket, i.e. it is not yet bound to a port.
        try (ServerSocket listeningSocket = new ServerSocket();
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Instruct the kernel to allow reusing a port lingering in the TIME_WAIT
            // state after it was recently closed.
            // Since the tester restarts your program quite often, setting SO_REUSEADDR
            // ensures that we don't run into 'Address already in use' errors.
            listeningSocket.setReuseAddress(true);

            // Bind the listening socket to a port.
            listeningSocket.bind(new InetSocketAddress(PORT));

            while (true) {
                // Wait for connection from client.
                // For each connection, creates a new "connection socket" that does
                // the actual communication.
                // The accept call blocks until there is a new connection, and
                // therefore needs to be outside the lambda passed to executor.execute.
                // Otherwise, the while loop is never blocked and spawns infinitely many
                // virtual threads instantly.
                Socket connectionSocket = listeningSocket.accept();
                executor.execute(() -> {
                    try (connectionSocket) {
                        System.out.println("Accepted new connection");
                        InputStream in = connectionSocket.getInputStream();
                        OutputStream out = connectionSocket.getOutputStream();
                        while (handleRequest(in, out)) {}
                    } catch (IOException e) {
                        System.out.println("IOException: " + e.getMessage());
                    }
                });
            }
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }

    /**
     * Handle a single request.
     *
     * @param in The input stream from which the incoming request can be read.
     * @param out The output stream to which the response can be written.
     * @return true if the connection should be persistent.
     * @throws IOException in case there is an {@link IOException}.
     */
    boolean handleRequest(InputStream in, OutputStream out) throws IOException {
        HttpRequest request;
        try {
            request = parseRequest(in);
        } catch (HttpException e) {
            sendResponse(out, HttpStatus.BAD_REQUEST, true, null, null, null);
            return false;
        }

        HttpStatus status;
        boolean closingConnection =
                Objects.equals(request.headers.getOrDefault("connection", null), "close");
        String acceptEncoding = request.headers.getOrDefault("accept-encoding", null);
        String contentEncoding = null;
        if (acceptEncoding != null) {
            List<String> encodingCandidates =
                    Arrays.stream(acceptEncoding.split(","))
                            .map(String::strip)
                            .filter(SUPPORTED_ENCODINGS::contains)
                            .toList();
            if (!encodingCandidates.isEmpty()) {
                contentEncoding = encodingCandidates.getFirst();
            }
        }
        String contentType = null;
        byte[] body = null;

        if (request.path == null) {
            // NOTE: When the status is NOT_FOUND, we normally do not close the connection
            // unless the request has the 'Connection: close' header.
            status = HttpStatus.NOT_FOUND;
        } else if (request.path.equals("/")) {
            status = HttpStatus.OK;
        } else if (request.path.startsWith("/echo/")) {
            String str = request.path.substring("/echo/".length());
            status = HttpStatus.OK;
            contentType = "text/plain";
            // According to RFC 6657, we use the US_ASCII charset for content type
            // text/plain by default, unless the content type header also explicitly
            // includes a charset.
            // For example: 'Content-Type: text/plain; charset=utf-8'
            body = str.getBytes(StandardCharsets.US_ASCII);
        } else if (request.path.startsWith("/user-agent")) {
            status = HttpStatus.OK;
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
                    status = HttpStatus.NOT_FOUND;
                } else {
                    status = HttpStatus.OK;
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
                    status = HttpStatus.BAD_REQUEST;
                    closingConnection = true;
                } else {
                    Files.write(filePath, request.body);
                    status = HttpStatus.CREATED;
                }
            } else {
                status = HttpStatus.METHOD_NOT_ALLOWED;
            }
        } else {
            // NOTE: When the status is NOT_FOUND, we normally do not close the connection
            // unless the request has the 'Connection: close' header.
            status = HttpStatus.NOT_FOUND;
        }
        sendResponse(out, status, closingConnection, contentEncoding, contentType, body);
        return !closingConnection;
    }

    private static byte[] compressGzip(byte[] data) throws IOException {
        if (data == null) {
            return null;
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(compressed)) {
            gzipOut.write(data);
        }
        // NOTE: When .close() is called on the GZIPOutputStream instance, it
        // writes some additional bytes to end the valid GZIP payload before
        // actually closing the stream. Therefore, we can only read from
        // the compressed output stream after the try-with-resource block.
        return compressed.toByteArray();
    }

    static void sendResponse(
            OutputStream out,
            HttpStatus status,
            boolean closingConnection,
            String contentEncoding,
            String contentType,
            byte[] body)
            throws IOException {
        // Use a byte buffer to minimize the number of syscalls taken to send the response.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        StringBuilder header = new StringBuilder();
        // Response line
        header.append("HTTP/1.1 ");
        header.append(status.code).append(" ").append(status.reason);
        header.append("\r\n");

        byte[] responseBody = body;

        // Header lines
        if (contentEncoding != null) {
            header.append("Content-Encoding: ").append(contentEncoding).append("\r\n");
            if (body != null && contentEncoding.equals("gzip")) {
                responseBody = compressGzip(body);
            }
        }
        if (contentType != null) {
            header.append("Content-Type: ").append(contentType).append("\r\n");
        }
        if (body != null) {
            header.append("Content-Length: ").append(responseBody.length).append("\r\n");
        }
        if (closingConnection) {
            header.append("Connection: close").append("\r\n");
        }

        header.append("\r\n");
        // Use the US_ASCII charset to encode the header to comply with RFC 9110 and RFC 9112.
        buf.write(header.toString().getBytes(StandardCharsets.US_ASCII));

        if (responseBody != null) {
            buf.write(responseBody);
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
            } else if (b == -1) {
                throw new HttpException("Unexpected EOF.");
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
            String fieldLine = parseRequestHeaderLine(in);
            if (fieldLine.isEmpty()) {
                break;
            }
            // According to RFC 9112, section 5, the syntax of each field line is:
            // field-line = field-name ":" optional-white-space field-value optional-white-space
            // RFC 9110 defines each optional-white-space to be zero or more spaces or tabs.
            int colonIdx = fieldLine.indexOf(':');
            String fieldName = fieldLine.substring(0, colonIdx);
            int valueIdx = colonIdx + 1;
            while (valueIdx < fieldLine.length()
                    && (fieldLine.charAt(valueIdx) == ' ' || fieldLine.charAt(valueIdx) == '\t')) {
                valueIdx++;
            }
            String fieldValue = fieldLine.substring(valueIdx);
            headers.put(fieldName.toLowerCase(), fieldValue);
        }

        byte[] body = null;

        if (headers.containsKey("content-length")) {
            int contentLength = Integer.parseInt(headers.get("content-length"));
            body = in.readNBytes(contentLength);
            if (body.length < contentLength) {
                throw new HttpException("Unexpected EOF while reading request body");
            }
        }

        return new HttpRequest(method, path, version, headers, body);
    }

    static class HttpStatus {
        static final HttpStatus OK = new HttpStatus(200, "OK");
        static final HttpStatus CREATED = new HttpStatus(201, "Created");
        static final HttpStatus BAD_REQUEST = new HttpStatus(400, "Bad Request");
        static final HttpStatus NOT_FOUND = new HttpStatus(404, "Not Found");
        static final HttpStatus METHOD_NOT_ALLOWED = new HttpStatus(405, "Method Not Allowed");

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
        byte[] body;

        HttpRequest(String method, String path, String version, Map<String, String> headers) {
            this(method, path, version, headers, null);
        }

        HttpRequest(
                String method,
                String path,
                String version,
                Map<String, String> headers,
                byte[] body) {
            this.method = method;
            this.path = path;
            this.version = version;
            this.headers = headers;
            this.body = body;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof HttpRequest other)) return false;
            return Objects.equals(method, other.method)
                    && Objects.equals(path, other.path)
                    && Objects.equals(version, other.version)
                    && Objects.equals(headers, other.headers)
                    && Arrays.equals(body, other.body);
        }

        @Override
        public int hashCode() {
            return Objects.hash(method, path, version, headers);
        }

        @Override
        public String toString() {
            return "HttpRequest{"
                    + "method='"
                    + method
                    + '\''
                    + ", path='"
                    + path
                    + '\''
                    + ", version='"
                    + version
                    + '\''
                    + ", headers="
                    + headers
                    + '}';
        }
    }
}
