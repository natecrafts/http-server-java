import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

public class HttpServer implements AutoCloseable {
    final Set<String> SUPPORTED_ENCODINGS = Set.of("gzip");
    private final int port;
    private final HttpHandler handler;
    private ServerSocket listeningSocket;

    public HttpServer(HttpHandler handler, int port) {
        this.handler = handler;
        this.port = port;
    }

    public HttpServer(HttpHandler handler) {
        // With port == 0, the kernel (atomically) picks and binds to any unused
        // ephemeral port.
        this(handler, 0);
    }

    public void run() {
        System.out.println("Starting HTTP server...");

        // Create a "listening socket" which accepts new connections.
        // This is initially an unbound socket, i.e. it is not yet bound to a port.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            if (listeningSocket == null) {
                bind();
            }
            while (true) {
                // Wait for connection from client.
                // For each connection, creates a new "connection socket" that does
                // the actual communication.
                // The accept call blocks until there is a new connection, and
                // therefore needs to be outside the lambda passed to executor.execute.
                // Otherwise, the while loop is never blocked and spawns infinitely many
                // virtual threads instantly.
                Socket connectionSocket = listeningSocket.accept();
                executor.execute(() -> handleConnection(connectionSocket));
            }
        } catch (SocketException e) {
            // When the server is closed through the close() method,
            // the handleRequest method also throws a Socket exception.
            if (listeningSocket != null && listeningSocket.isClosed()) {
                System.out.println("Server closed.");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void handleConnection(Socket connectionSocket) {
        try (connectionSocket) {
            System.out.println("Accepted new connection");
            InputStream in = connectionSocket.getInputStream();
            OutputStream out = connectionSocket.getOutputStream();
            while (handleRequest(in, out)) {}
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }

    int bind() throws IOException {
        listeningSocket = new ServerSocket();
        // Instruct the kernel to allow reusing a port lingering in the TIME_WAIT
        // state after it was recently closed.
        // Since the tester restarts your program quite often, setting SO_REUSEADDR
        // ensures that we don't run into 'Address already in use' errors.
        listeningSocket.setReuseAddress(true);
        listeningSocket.bind(new InetSocketAddress(port));
        return listeningSocket.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        if (listeningSocket != null) {
            listeningSocket.close();
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
        Request request;
        try {
            request = parseRequest(in);
        } catch (HttpException e) {
            Response response = new Response(HttpStatus.BAD_REQUEST, null, null);
            sendResponse(out, response, true, null);
            return false;
        }

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
        Response response = handler.handle(request);
        sendResponse(out, response, closingConnection, contentEncoding);
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
            OutputStream out, Response response, boolean closingConnection, String contentEncoding)
            throws IOException {
        // Use a byte buffer to minimize the number of syscalls taken to send the response.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        StringBuilder header = new StringBuilder();
        // Response line
        header.append("HTTP/1.1 ");
        header.append(response.status.code).append(" ").append(response.status.reason);
        header.append("\r\n");

        byte[] responseBody = response.body;

        // Header lines
        if (contentEncoding != null) {
            header.append("Content-Encoding: ").append(contentEncoding).append("\r\n");
            if (response.body != null && contentEncoding.equals("gzip")) {
                responseBody = compressGzip(response.body);
            }
        }
        if (response.contentType != null) {
            header.append("Content-Type: ").append(response.contentType).append("\r\n");
        }
        if (response.body != null) {
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

    static Request parseRequest(InputStream in) throws IOException, HttpException {
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

        return new Request(method, path, version, headers, body);
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

    public static class Request {
        String method;
        String path;
        String version;
        Map<String, String> headers;
        byte[] body;

        Request(String method, String path, String version, Map<String, String> headers) {
            this(method, path, version, headers, null);
        }

        Request(
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
            if (!(obj instanceof Request other)) return false;
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

    public static class Response {
        HttpStatus status;
        String contentType;
        byte[] body;

        public Response(HttpStatus status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
