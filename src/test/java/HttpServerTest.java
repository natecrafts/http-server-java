import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class HttpServerTest {
    @Test
    void shouldSend200OkResponse() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpServer.sendResponse(out, HttpServer.HttpStatus.OK, null, null);

        String expected = "HTTP/1.1 200 OK\r\n\r\n";
        String actual = out.toString(StandardCharsets.US_ASCII);
        assertEquals(expected, actual);
    }

    private static final List<Arguments> PARSE_REQUEST_CASES =
            List.of(
                    Arguments.of(
                            "GET /",
                            "GET / HTTP/1.1\r\n"
                                    + "Host: localhost:4221\r\n"
                                    + "User-Agent: curl/8.7.1\r\n"
                                    + "Accept: */*\r\n\r\n",
                            new HttpServer.HttpRequest(
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
                            new HttpServer.HttpRequest(
                                    "GET",
                                    "/abcdefg",
                                    "HTTP/1.1",
                                    Map.ofEntries(
                                            Map.entry("host", "localhost:4221"),
                                            Map.entry("user-agent", "curl/8.7.1"),
                                            Map.entry("accept", "*/*")))));

    @ParameterizedTest(name = "{0}")
    @FieldSource("PARSE_REQUEST_CASES")
    void shouldParseRequest(
            String caseName, String rawRequest, HttpServer.HttpRequest expectedRequest)
            throws IOException, HttpServer.HttpException {
        ByteArrayInputStream in =
                new ByteArrayInputStream(rawRequest.getBytes(StandardCharsets.US_ASCII));

        HttpServer.HttpRequest request = HttpServer.parseRequest(in);

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
                    Arguments.of("Empty stream -> 400", "", "HTTP/1.1 400 Bad Request\r\n\r\n"),
                    Arguments.of(
                            "Truncated request line -> 400",
                            "GET / HTTP/1.1",
                            "HTTP/1.1 400 Bad Request\r\n\r\n"),
                    Arguments.of(
                            "Truncated headers -> 400",
                            "GET / HTTP/1.1\r\nHost: localhost:4221\r\n",
                            "HTTP/1.1 400 Bad Request\r\n\r\n"),
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
                            "HTTP/1.1 404 Not Found\r\n\r\n"));

    @ParameterizedTest(name = "{0}")
    @FieldSource("HANDLE_REQUEST_CASES")
    void shouldHandleRequest(String caseName, String rawRequest, String expectedResponse)
            throws IOException {
        byte[] request = rawRequest.getBytes(StandardCharsets.US_ASCII);
        ByteArrayInputStream in = new ByteArrayInputStream(request);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpServer server = new HttpServer();
        server.handleRequest(in, out);

        assertEquals(expectedResponse, out.toString(StandardCharsets.US_ASCII));
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
        Path serveDir = Files.createDirectory(tempDir.resolve("served"));

        Path regularFile = serveDir.resolve("foo");
        String regularContent = "hello world";
        Files.writeString(regularFile, regularContent, StandardCharsets.US_ASCII);

        // The secret file is outside the serve dir. Use it to test for path traversal.
        Path secretFile = tempDir.resolve("secret.txt");
        String secretContent = "top secret";
        Files.writeString(secretFile, secretContent, StandardCharsets.US_ASCII);

        byte[] request = rawRequest.getBytes(StandardCharsets.US_ASCII);

        ByteArrayInputStream in = new ByteArrayInputStream(request);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpServer server = new HttpServer(serveDir);
        server.handleRequest(in, out);
        assertEquals(expectedResponse, out.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void shouldHandlePostFilesRequest(@TempDir Path tempDir) throws IOException {
        Path servedDir = Files.createDirectory(tempDir.resolve("served"));
        String requestBody = "12345";
        String request =
                "POST /files/number HTTP/1.1\r\n"
                        + "Host: localhost:4221\r\n"
                        + "User-Agent: curl/8.7.1\r\n"
                        + "Accept: */*\r\n"
                        + "Content-Type: application/octet-stream\r\n"
                        + "Content-Length: 5\r\n\r\n"
                        + requestBody;

        ByteArrayInputStream in =
                new ByteArrayInputStream(request.getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        new HttpServer(servedDir).handleRequest(in, out);
        String fileContent =
                Files.readString(servedDir.resolve("number"), StandardCharsets.US_ASCII);

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

        HttpServer server = new HttpServer();
        boolean persistingConnection = server.handleRequest(in, out);
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

        HttpServer server = new HttpServer();
        boolean persistingConnection = server.handleRequest(in, out);
        assertFalse(persistingConnection);
    }
}
