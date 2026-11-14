import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

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
            }
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }
}
