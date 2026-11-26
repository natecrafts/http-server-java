package tcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * An OSI Layer 4 (transport) TCP server that accepts connections and dispatches each
 * connection to a {@link TcpConnectionHandler}.
 * The server handles connections concurrently, each on a virtual thread.
 */
public class TcpServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TcpServer.class);

    /**
     * The connection handler, which may be one that adheres to a protocol built
     * on top of TCP (e.g. HTTP).
     */
    private final TcpConnectionHandler connectionHandler;

    /**
     * The initial port value that this server should bind to.
     * If this is 0, the kernel atomically picks and binds to any unused ephemeral port.
     */
    private final int initialPort;

    /**
     * A listening socket to accept new connections.
     */
    private ServerSocket listeningSocket;

    public TcpServer(TcpConnectionHandler connectionHandler, int initialPort) {
        this.connectionHandler = connectionHandler;
        this.initialPort = initialPort;
    }

    public TcpServer(TcpConnectionHandler connectionHandler) {
        // Set the initial port to 0 to pick an unused ephemeral port.
        this(connectionHandler, 0);
    }

    public void run() {
        log.info("Starting TCP server...");

        // Only create a listeningSocket if it is not yet created.
        if (listeningSocket == null) {
            try {
                bind();
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Cannot bind the server to port " + initialPort + ".", e);
            }
        }

        log.info(
                "Running TCP server at {}:{}",
                listeningSocket.getInetAddress().getHostAddress(),
                getPort());

        // Create a virtual thread factory to pass into the ThreadPerTaskExecutor.
        // This factory gives each thread a name for better logging.
        ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("conn-", 0).factory();

        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
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
            // Closing the listeningSocket via close() interrupts the blocked accept() call
            // with a SocketException. This is an expected shutdown path, so we just log
            // and exit gracefully.
            if (listeningSocket.isClosed()) {
                log.info("Server closed.");
            } else {
                throw new UncheckedIOException(
                        "Socket error while accepting connections on port " + getPort() + ".", e);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "I/O error while accepting connections on port " + getPort() + ".", e);
        }
    }

    /**
     * Creates the listening socket and binds it to a local IP address and TCP port.
     * This must be called before the server can handle requests and only called once
     * for a server instance.
     */
    public void bind() throws IOException {
        if (listeningSocket != null) {
            throw new IllegalStateException("Server is already bound to port " + getPort() + ".");
        }
        // Create a "listening socket" which accepts new connections.
        // This is initially an unbound socket, i.e. it is not yet bound to a port.
        listeningSocket = new ServerSocket();
        try {
            // Instruct the kernel to allow reusing a port lingering in the TIME_WAIT
            // state after it was recently closed.
            // Since the tester restarts your program quite often, setting SO_REUSEADDR
            // ensures that we don't run into 'Address already in use' errors.
            listeningSocket.setReuseAddress(true);
            listeningSocket.bind(new InetSocketAddress(initialPort));
        } catch (IOException e) {
            // When the listeningSocket is created, it occupies a file descriptor.
            // If we do not call .close() manually, we have to wait for the GC to
            // clean up the listeningSocket to free the file descriptor.
            // Unix OSes normally have a file descriptor limit. It is good practice
            // to free up file descriptors if we do not use them.
            // Run ulimit -n to see the max number of file descriptors a single
            // process can have open simultaneously.
            listeningSocket.close();
            // Set the listening socket back to null to avoid referencing a closed socket.
            listeningSocket = null;
            throw e;
        }
    }

    /**
     * This must be called after {@link #bind}.
     * @return The port that the listening socket of this server is bound to.
     */
    public int getPort() {
        if (listeningSocket == null) {
            throw new IllegalStateException(
                    "The listening socket has not been created and bound to a port.");
        }
        return listeningSocket.getLocalPort();
    }

    /**
     * Handles a TCP connection.
     * @param connectionSocket The connection socket. After this ends, the connection socket
     *                         also gets closed. In a way, the method becomes the "owner" of
     *                         the connectionSocket object.
     */
    void handleConnection(Socket connectionSocket) {
        try (connectionSocket) {
            InputStream in = connectionSocket.getInputStream();
            OutputStream out = connectionSocket.getOutputStream();
            connectionHandler.handle(in, out);
        } catch (IOException e) {
            // If IOException is thrown here, we assume this is the fault of the client
            // and log a DEBUG level message.
            log.debug(
                    "I/O error while handling connection on port {}: {}",
                    getPort(),
                    e.getMessage());
        }
    }

    /**
     * Closes the listening socket of the server and stops listening to new requests.
     */
    @Override
    public void close() throws IOException {
        if (listeningSocket != null) {
            listeningSocket.close();
        }
    }
}
