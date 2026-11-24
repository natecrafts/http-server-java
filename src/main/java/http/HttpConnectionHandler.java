package http;

import tcp.TcpConnectionHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * An OSI Layer 7 (application) HTTP/1.1 connection handler.
 * Routing is delegated to an {@link HttpRequestHandler}.
 * Supported features:
 * <ul>
 *     <li>Keep alive</li>
 *     <li>GZIP compression</li>
 * </ul>
 */
public class HttpConnectionHandler implements TcpConnectionHandler {
    final Set<String> SUPPORTED_ENCODINGS = Set.of("gzip");

    private final HttpRequestHandler requestHandler;

    public HttpConnectionHandler(HttpRequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    @Override
    public void handle(InputStream in, OutputStream out) throws IOException {
        while (handleRequest(in, out)) {}
    }

    /**
     * Handles a single request.
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
            HttpResponse response = new HttpResponse(HttpStatus.BAD_REQUEST, null, null);
            sendResponse(out, response, false, null);
            return false;
        }

        boolean keepAlive =
                !Objects.equals(request.headers.getOrDefault("connection", null), "close");
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
        HttpResponse response = requestHandler.handle(request);
        sendResponse(out, response, keepAlive, contentEncoding);
        return keepAlive;
    }

    /**
     * Parses an HTTP request.
     */
    static HttpRequest parseRequest(InputStream in) throws HttpException {
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
            try {
                body = in.readNBytes(contentLength);
            } catch (IOException e) {
                throw new HttpException(
                        "Failed to read " + contentLength + " byte(s) of the request body.");
            }
            if (body.length < contentLength) {
                throw new HttpException("Unexpected EOF while reading request body");
            }
        }

        return new HttpRequest(method, path, version, headers, body);
    }

    /**
     * Parses a single line in the header of an HTTP request.
     * For reference, see RFC 9112:
     * @param in The input stream.
     * @return The next line in the header of an HTTP request.
     * @throws HttpException if there is an error parsing the HTTP request.
     */
    static String parseRequestHeaderLine(InputStream in) throws HttpException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            int b;
            try {
                b = in.read();
            } catch (IOException e) {
                throw new HttpException("I/O error reading the header: " + e.getMessage() + ".");
            }
            if (b == '\r') {
                int next;
                try {
                    next = in.read();
                } catch (IOException e) {
                    throw new HttpException(
                            "I/O error reading the header: " + e.getMessage() + ".");
                }
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

    static void sendResponse(
            OutputStream out, HttpResponse response, boolean keepAlive, String contentEncoding)
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
        if (!keepAlive) {
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
}
