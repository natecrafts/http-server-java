import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
                                    + "foobar/1.2.3"));

    @ParameterizedTest(name = "{0}")
    @FieldSource("HANDLE_REQUEST_CASES")
    void shouldHandleRequest(String caseName, String request, String expectedResponse)
            throws IOException, HttpServer.HttpException {
        ByteArrayInputStream in =
                new ByteArrayInputStream(request.getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpServer.handleRequest(in, out);

        assertEquals(expectedResponse, out.toString(StandardCharsets.US_ASCII));
    }
}
