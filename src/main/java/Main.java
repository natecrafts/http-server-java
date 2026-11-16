import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class Main {
    public static void main(String[] args) {
        // Print statements can be used for debugging and are visible when running tests.
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
            final int PORT = 4221;
            listeningSocket.bind(new InetSocketAddress(PORT));

            // Wait for connection from client.
            // For each connection, creates a new "connection socket" that
            // does the actual communication.
            try (Socket connectionSocket = listeningSocket.accept()) {
                System.out.println("Accepted new connection");
                InputStream in = connectionSocket.getInputStream();
                OutputStream out = connectionSocket.getOutputStream();
                sendResponse(out, HttpStatus.OK, null, null);
            }
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }

    static void sendResponse(OutputStream out, HttpStatus status, String contentType, byte[] body)
            throws IOException {
        // Use a byte buffer to minimize the number of syscalls taken to send the response.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ");
        header.append(status.code).append(" ").append(status.reason);
        header.append("\r\n");

        // TODO: Headers

        header.append("\r\n");
        // Use US_ASCII to comply with RFC 9110 and RFC 9112.
        buf.write(header.toString().getBytes(StandardCharsets.US_ASCII));

        // TODO: Write body
        // if (body != null) {
        //     buf.write(body);
        // }

        out.write(buf.toByteArray());
        out.flush();
    }

    static class HttpStatus {
        static final HttpStatus OK = new HttpStatus(200, "OK");

        private final int code;
        private final String reason;

        HttpStatus(int code, String reason) {
            this.code = code;
            this.reason = reason;
        }
    }
}
