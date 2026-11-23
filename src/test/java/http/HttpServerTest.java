package http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import codecrafters.CodeCraftersHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;

public class HttpServerTest {
    private static final List<Arguments> PARSE_REQUEST_CASES =
            List.of(
                    Arguments.of(
                            "GET /",
                            "GET / HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n\r\n",
                            new HttpRequest(
                                    "GET",
                                    "/",
                                    "HTTP/1.1",
                                    Map.ofEntries(
                                            Map.entry("host", "localhost:4221"),
                                            Map.entry("user-agent", "curl/8.7.1"),
                                            Map.entry("accept", "*/*")))),
                    Arguments.of(
                            "GET /abcdefg",
                            "GET /abcdefg HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n\r\n",
                            new HttpRequest(
                                    "GET",
                                    "/abcdefg",
                                    "HTTP/1.1",
                                    Map.ofEntries(
                                            Map.entry("host", "localhost:4221"),
                                            Map.entry("user-agent", "curl/8.7.1"),
                                            Map.entry("accept", "*/*")))));

    @ParameterizedTest(name = "{0}")
    @FieldSource("PARSE_REQUEST_CASES")
    void shouldParseRequest(String caseName, String rawRequest, HttpRequest expectedRequest)
            throws HttpException {
        ByteArrayInputStream in =
                new ByteArrayInputStream(rawRequest.getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = HttpServer.parseRequest(in);

        assertEquals(expectedRequest, request);
    }

    private static final List<Arguments> HANDLE_REQUEST_CASES =
            List.of(
                    Arguments.of(
                            // caseName
                            "GET / -> 200",
                            // request
                            "GET / HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n\r\n",
                            // response
                            "HTTP/1.1 200 OK\r\n\r\n"),
                    Arguments.of(
                            "GET /abcdefg -> 404",
                            "GET /abcdefg HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n\r\n",
                            "HTTP/1.1 404 Not Found\r\n\r\n"),
                    Arguments.of(
                            "GET /echo -> 404",
                            "GET /echo HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n\r\n",
                            "HTTP/1.1 404 Not Found\r\n\r\n"),
                    // 400 Bad Request
                    Arguments.of(
                            "Empty stream -> 400",
                            "",
                            "HTTP/1.1 400 Bad Request\r\n" + "Connection: close\r\n\r\n"),
                    Arguments.of(
                            "Truncated request line -> 400",
                            "GET / HTTP/1.1",
                            "HTTP/1.1 400 Bad Request\r\n" + "Connection: close\r\n\r\n"),
                    Arguments.of(
                            "Truncated headers -> 400",
                            "GET / HTTP/1.1\r\nHost: localhost:4221\r\n",
                            "HTTP/1.1 400 Bad Request\r\n" + "Connection: close\r\n\r\n"),
                    // /echo/{msg}
                    Arguments.of(
                            "GET /echo/abc -> 200",
                            "GET /echo/abc HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n\r\n",
                            "HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: text/plain\r\n"
                                    + "Content-Length: 3\r\n\r\n"
                                    + "abc"),
                    Arguments.of(
                            "GET /echo/ -> 200",
                            "GET /echo/ HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n\r\n",
                            "HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: text/plain\r\n"
                                    + "Content-Length: 0\r\n\r\n"),
                    // /user-agent
                    Arguments.of(
                            "GET /user-agent/ -> 200",
                            "GET /user-agent/ HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: foobar/1.2.3\r\n"
                                    + "Accept: */*\r\n\r\n",
                            "HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: text/plain\r\n"
                                    + "Content-Length: 12\r\n\r\n"
                                    + "foobar/1.2.3"),
                    // /files/{filename} without --directory
                    Arguments.of(
                            "GET /files/foo without directory -> 404",
                            "GET /files/foo HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n\r\n",
                            "HTTP/1.1 404 Not Found\r\n\r\n"),
                    // Accept-Encoding and Content-Encoding (unsupported encodings)
                    Arguments.of(
                            "Request with Accept-Encoding: invalid-encoding -> Response without"
                                    + " Content-Encoding",
                            "GET /echo/abc HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n"
                                    + "Accept-Encoding: invalid-encoding\r\n\r\n",
                            "HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: text/plain\r\n"
                                    + "Content-Length: 3\r\n\r\n"
                                    + "abc"),
                    Arguments.of(
                            "Request with multiple Accept-Encoding values, none is valid",
                            "GET /echo/abc HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n"
                                    + "Accept-Encoding: invalid-encoding-1, invalid-encoding-2,"
                                    + " invalid-encoding-3\r\n\r\n",
                            "HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: text/plain\r\n"
                                    + "Content-Length: 3\r\n\r\n"
                                    + "abc"));

    @ParameterizedTest(name = "{0}")
    @FieldSource("HANDLE_REQUEST_CASES")
    void shouldHandleRequest(String caseName, String rawRequest, String expectedResponse)
            throws IOException {
        byte[] request = rawRequest.getBytes(StandardCharsets.US_ASCII);
        ByteArrayInputStream in = new ByteArrayInputStream(request);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpHandler codecraftersHandler = new CodeCraftersHandler();
        try (HttpServer server = new HttpServer(codecraftersHandler)) {
            server.handleRequest(in, out);
        }

        assertEquals(expectedResponse, out.toString(StandardCharsets.US_ASCII));
    }

    private static final List<Arguments> HANDLE_GZIP_REQUEST_CASES =
            List.of(
                    Arguments.of(
                            "Accept-Encoding: gzip",
                            "GET /echo/abc HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n"
                                    + "Accept-Encoding: gzip\r\n\r\n"),
                    Arguments.of(
                            "Multiple Accept-Encoding values with gzip",
                            "GET /echo/abc HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n"
                                    + "Accept-Encoding: invalid-encoding-1, gzip,"
                                    + " invalid-encoding-2\r\n\r\n"));

    @ParameterizedTest(name = "{0}")
    @FieldSource("HANDLE_GZIP_REQUEST_CASES")
    void shouldHandleGzipCompressedRequest(String caseName, String rawRequest) throws IOException {
        byte[] request = rawRequest.getBytes(StandardCharsets.US_ASCII);
        ByteArrayInputStream in = new ByteArrayInputStream(request);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpHandler codecraftersHandler = new CodeCraftersHandler();
        try (HttpServer server = new HttpServer(codecraftersHandler)) {
            server.handleRequest(in, out);
        }

        String rawExpectedBody = "abc";
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(compressed)) {
            gzipOut.write(rawExpectedBody.getBytes(StandardCharsets.US_ASCII));
        }
        byte[] expectedBody = compressed.toByteArray();

        String expectedHeaders =
                "HTTP/1.1 200 OK\r\n"
                        + "Content-Encoding: gzip\r\n"
                        + "Content-Type: text/plain\r\n"
                        + "Content-Length: "
                        + expectedBody.length
                        + "\r\n\r\n";

        ByteArrayOutputStream expectedResponse = new ByteArrayOutputStream();
        expectedResponse.write(expectedHeaders.getBytes(StandardCharsets.US_ASCII));
        expectedResponse.write(expectedBody);

        assertArrayEquals(expectedResponse.toByteArray(), out.toByteArray());
    }

    private static final List<Arguments> HANDLE_FILE_REQUEST_CASES =
            List.of(
                    Arguments.of(
                            "Existing file -> 200",
                            "GET /files/foo HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n\r\n",
                            "HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: application/octet-stream\r\n"
                                    + "Content-Length: 11\r\n\r\n"
                                    + "hello world"),
                    Arguments.of(
                            "Missing file -> 404",
                            "GET /files/missing HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n\r\n",
                            "HTTP/1.1 404 Not Found\r\n\r\n"),
                    Arguments.of(
                            "Directory path -> 404",
                            "GET /files/ HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n\r\n",
                            "HTTP/1.1 404 Not Found\r\n\r\n"),
                    Arguments.of(
                            "Path traversal -> 404",
                            "GET /files/../secret.txt HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n\r\n",
                            "HTTP/1.1 404 Not Found\r\n\r\n"));

    @ParameterizedTest(name = "{0}")
    @FieldSource("HANDLE_FILE_REQUEST_CASES")
    void shouldHandleGetFilesRequest(
            String caseName, String rawRequest, String expectedResponse, @TempDir Path tempDir)
            throws IOException {
        Path servedDir = Files.createDirectory(tempDir.resolve("served"));

        Path regularFile = servedDir.resolve("foo");
        String regularContent = "hello world";
        Files.writeString(regularFile, regularContent, StandardCharsets.US_ASCII);

        // The secret file is outside the serve dir. Use it to test for path traversal.
        Path secretFile = tempDir.resolve("secret.txt");
        String secretContent = "top secret";
        Files.writeString(secretFile, secretContent, StandardCharsets.US_ASCII);

        byte[] request = rawRequest.getBytes(StandardCharsets.US_ASCII);

        ByteArrayInputStream in = new ByteArrayInputStream(request);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpHandler codecraftersHandler = new CodeCraftersHandler(servedDir);
        try (HttpServer server = new HttpServer(codecraftersHandler)) {
            server.handleRequest(in, out);
        }
        assertEquals(expectedResponse, out.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void shouldHandlePostFilesRequest(@TempDir Path tempDir) throws IOException {
        Path servedDir = Files.createDirectory(tempDir.resolve("served"));
        String requestBody = "12345";
        String rawRequest =
                "POST /files/number HTTP/1.1\r\n"
                        + "Host: localhost:4221\r\n"
                        + "User-Agent: curl/8.7.1\r\n"
                        + "Accept: */*\r\n"
                        + "Content-Type: application/octet-stream\r\n"
                        + "Content-Length: 5\r\n\r\n"
                        + requestBody;
        byte[] request = rawRequest.getBytes(StandardCharsets.US_ASCII);

        ByteArrayInputStream in = new ByteArrayInputStream(request);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpHandler codecraftersHandler = new CodeCraftersHandler(servedDir);
        try (HttpServer server = new HttpServer(codecraftersHandler)) {
            server.handleRequest(in, out);
        }
        Path filePath = servedDir.resolve("number");
        String fileContent = Files.readString(filePath, StandardCharsets.US_ASCII);

        assertEquals("HTTP/1.1 201 Created\r\n\r\n", out.toString(StandardCharsets.US_ASCII));
        assertEquals(requestBody, fileContent);
    }

    @Test
    void shouldPersistConnectionByDefault() throws IOException {
        String rawRequest =
                "GET / HTTP/1.1\r\n"
                        + "Host: localhost:4221\r\n"
                        + "User-Agent: curl/8.7.1\r\n"
                        + "Accept: */*\r\n\r\n";
        byte[] request = rawRequest.getBytes(StandardCharsets.US_ASCII);

        ByteArrayInputStream in = new ByteArrayInputStream(request);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpHandler codecraftersHandler = new CodeCraftersHandler();
        boolean persistingConnection;
        try (HttpServer server = new HttpServer(codecraftersHandler)) {
            persistingConnection = server.handleRequest(in, out);
        }
        assertTrue(persistingConnection);
    }

    @Test
    void shouldCloseConnectionWithHeader() throws IOException {
        String rawRequest =
                "GET / HTTP/1.1\r\n"
                        + "Host: localhost:4221\r\n"
                        + "User-Agent: curl/8.7.1\r\n"
                        + "Accept: */*\r\n"
                        + "Connection: close\r\n\r\n";
        byte[] request = rawRequest.getBytes(StandardCharsets.US_ASCII);

        ByteArrayInputStream in = new ByteArrayInputStream(request);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpHandler codecraftersHandler = new CodeCraftersHandler();
        boolean persistingConnection;
        try (HttpServer server = new HttpServer(codecraftersHandler)) {
            persistingConnection = server.handleRequest(in, out);
        }
        assertFalse(persistingConnection);
    }

    @Test
    void shouldHandlePersistentConnection() throws IOException {
        String rawRequest1 =
                "GET /echo/first HTTP/1.1\r\n"
                        + "Host: localhost:4221\r\n"
                        + "User-Agent: curl/8.7.1\r\n"
                        + "Accept: */*\r\n\r\n";
        String rawRequest2 =
                "GET /user-agent HTTP/1.1\r\n"
                        + "Host: localhost:4221\r\n"
                        + "User-Agent: foobar/1.2.3\r\n"
                        + "Accept: */*\r\n"
                        + "Connection: close\r\n\r\n";
        String rawRequests = rawRequest1 + rawRequest2;
        byte[] requests = rawRequests.getBytes(StandardCharsets.US_ASCII);

        ByteArrayInputStream in = new ByteArrayInputStream(requests);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpHandler codecraftersHandler = new CodeCraftersHandler();
        String expectedResponses;
        try (HttpServer server = new HttpServer(codecraftersHandler)) {

            String expectedResponse1 =
                    "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/plain\r\n"
                            + "Content-Length: 5\r\n\r\n"
                            + "first";
            String expectedResponse2 =
                    "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/plain\r\n"
                            + "Content-Length: 12\r\n"
                            + "Connection: close\r\n\r\n"
                            + "foobar/1.2.3";

            expectedResponses = expectedResponse1 + expectedResponse2;

            assertTrue(server.handleRequest(in, out));
            assertFalse(server.handleRequest(in, out));
        }
        assertEquals(expectedResponses, out.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void shouldHandleConcurrentConnections() {
        int latchTimeoutSeconds = 2;
        CountDownLatch twoConcurrentRequestsLatch = new CountDownLatch(2);
        HttpHandler twoConcurrentRequestsHandler =
                request -> {
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
                    return new HttpResponse(HttpStatus.OK, null, null);
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
                HttpServer server = new HttpServer(twoConcurrentRequestsHandler)) {
            server.bind();
            int port = server.getPort();
            executor.execute(server::run);

            Callable<byte[]> clientTask =
                    () -> {
                        try (Socket socket = new Socket("127.0.0.1", port)) {
                            OutputStream clientOut = socket.getOutputStream();
                            String rawRequest =
                                    "GET / HTTP/1.1\r\n"
                                            + "Host: localhost\r\n"
                                            + "Connection: close\r\n\r\n";
                            clientOut.write(rawRequest.getBytes(StandardCharsets.US_ASCII));
                            clientOut.flush();
                            return socket.getInputStream().readAllBytes();
                        }
                    };

            Future<byte[]> task1 = executor.submit(clientTask);
            Future<byte[]> task2 = executor.submit(clientTask);

            byte[] expectedResponse =
                    "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII);

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
