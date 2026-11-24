package tcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.util.concurrent.TimeoutException;

public class TcpServerTest {
    @Test
    void shouldHandleConcurrentConnections() {
        int latchTimeoutSeconds = 2;
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
                                latchTimeoutSeconds, TimeUnit.SECONDS)) {
                            throw new AssertionError("Requests were not handled concurrently.");
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    out.write("ok".getBytes(StandardCharsets.US_ASCII));
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

            Callable<byte[]> clientTask =
                    () -> {
                        try (Socket socket = new Socket("127.0.0.1", port)) {
                            // Read the "ok" message from the server.
                            return socket.getInputStream().readAllBytes();
                        }
                    };

            Future<byte[]> task1 = executor.submit(clientTask);
            Future<byte[]> task2 = executor.submit(clientTask);

            byte[] expectedResponse = "ok".getBytes(StandardCharsets.US_ASCII);

            // Make sure the timeout of each response is larger than the timeout of the
            // count-down latch used to test whether the server can handle concurrent requests.
            // This ensures that:
            // - In case the server does not properly handle concurrent requests, the timeout
            //   of the count-down latch is fired first and indicates the issue.
            // - As the two timeouts are different, we make sure the test is deterministic and
            //   avoid any race condition where either timeout could fire first, which makes the
            //   result of the test non-deterministic in case there is a regression.
            int responseTimeOutSeconds = latchTimeoutSeconds + 1;
            byte[] response1;
            byte[] response2;
            try {
                response1 = task1.get(responseTimeOutSeconds, TimeUnit.SECONDS);
                response2 = task2.get(responseTimeOutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                fail("Request timed out.", e);
                return;
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
