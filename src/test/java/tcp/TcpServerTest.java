package tcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpServerTest {
    /**
     * Read all bytes from a localhost server using a client socket.
     * @param port                The port on which the server is listening for connection.
     * @param socketTimeoutMillis The timeout for the client socket.
     * @return                    All bytes read from the server.
     */
    byte[] readAllFromServer(int port, int socketTimeoutMillis) throws IOException {
        try (Socket clientSocket = new Socket("127.0.0.1", port)) {
            // Set a timeout for the client socket in case a regression in TcpServer causes
            // the test to stall. This may happen in one of these cases:
            // - The TcpServer fails to call .close() on the connection socket.
            // - The TcpServer unexpectedly stops accepting connections.
            clientSocket.setSoTimeout(socketTimeoutMillis);
            // Read the "ok" message from the server.
            return clientSocket.getInputStream().readAllBytes();
        }
    }

    /**
     * This test makes sure if the first request results in the handler throwing an IOException,
     * the TcpServer still allows for the second request to be handled successfully.
     */
    @Test
    void shouldHandleIOExceptionFromTcpConnectionHandler() {
        final byte[] expectedResponse = "ok".getBytes(StandardCharsets.US_ASCII);

        AtomicInteger counter = new AtomicInteger(0);
        // This handler throws an IOException on the first request and replies successfully on
        // the second request.
        TcpConnectionHandler twoConnectionsHandler =
                (_, out) -> {
                    // NOTE: Here we need to add and read in one atomic operation to avoid any
                    // TOCTOU-related issues.
                    if (counter.addAndGet(1) == 1) {
                        throw new IOException("Cannot handle first request");
                    }
                    out.write(expectedResponse);
                    out.flush();
                };

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                TcpServer server = new TcpServer(twoConnectionsHandler)) {
            server.bind();
            int port = server.getPort();
            executor.execute(server::run);

            final int clientSocketTimeoutMillis = 2000;
            byte[] response1 = readAllFromServer(port, clientSocketTimeoutMillis);
            byte[] response2 = readAllFromServer(port, clientSocketTimeoutMillis);
            assertArrayEquals(new byte[0], response1);
            assertArrayEquals(expectedResponse, response2);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldThrowWhenGettingPortBeforeBind() {
        try (TcpServer server = new TcpServer((_, _) -> {})) {
            assertThrows(IllegalStateException.class, server::getPort);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldHandleConcurrentConnections() {
        final byte[] expectedResponse = "ok".getBytes(StandardCharsets.US_ASCII);

        final int latchTimeoutMillis = 2000;
        CountDownLatch twoConcurrentRequestsLatch = new CountDownLatch(2);
        // This connection handler simply response with the message "ok".
        TcpConnectionHandler twoConcurrentConnectionsHandler =
                (_, out) -> {
                    twoConcurrentRequestsLatch.countDown();
                    try {
                        // If the two requests are handled concurrently, this count down latch
                        // should reach 0 instantaneously during the handling of each request
                        // instead of blocking.
                        // We set a timeout for the count-down latch, and if the timeout is reached,
                        // then we know the server does not handle concurrent requests correctly.
                        if (!twoConcurrentRequestsLatch.await(
                                latchTimeoutMillis, TimeUnit.MILLISECONDS)) {
                            throw new AssertionError("Requests were not handled concurrently.");
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    out.write(expectedResponse);
                    out.flush();
                };

        // The test thread needs to carry out these steps: (1) start the server, (2) submit two
        // concurrent client requests while the server is running, (3) assert the responses,
        // and (4) close the server. Steps (1) and (2) need to run concurrently.
        // However, calling server.run() directly on the test thread would block the run method
        // in its while (true) loop and prevent any of the remaining steps from running.
        // Therefore, we run the server on a separate background thread using a task spawned
        // by a VirtualThreadPerTaskExecutor.
        // When the try-with-resources block exits, server.close() closes listeningSocket,
        // which interrupts the blocked listeningSocket.accept() call with a SocketException
        // and breaks out of the while (true) loop.
        // NOTE: Resources allocated through try-with-resources are closed in reverse order of
        // declaration. The executor needs to be closed last, as it blocks until all tasks are
        // finished. In this case, tasks include the server task and the two client request tasks.
        // The executor needs to be closed after the server is closed and the server task finishes.
        // Otherwise, the executor waits for the server to be closed, but the server is never
        // closed until its own close() method is called, causing a deadlock.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                TcpServer server = new TcpServer(twoConcurrentConnectionsHandler)) {
            server.bind();
            int port = server.getPort();
            executor.execute(server::run);

            // Make sure the timeout of the client socket is larger than the timeout of the
            // count-down latch used to test whether the server can handle concurrent requests.
            // This ensures that:
            // - In case the server does not properly handle concurrent requests, the timeout
            //   of the count-down latch is fired first and indicates the issue.
            // - As the two timeouts are different, we make sure the test is deterministic and
            //   avoid any race condition where either timeout could fire first, which makes the
            //   result of the test non-deterministic in case there is a regression.
            final int socketTimeoutMillis = 3000;
            Callable<byte[]> clientTask = () -> readAllFromServer(port, socketTimeoutMillis);

            Future<byte[]> task1 = executor.submit(clientTask);
            Future<byte[]> task2 = executor.submit(clientTask);

            byte[] response1;
            byte[] response2;
            try {
                response1 = task1.get();
                response2 = task2.get();
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }
            assertArrayEquals(expectedResponse, response1);
            assertArrayEquals(expectedResponse, response2);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
