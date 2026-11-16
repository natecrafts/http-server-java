import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class HttpServerTest {
    @Test
    void shouldSend200OkResponse() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpServer.sendResponse(out, HttpServer.HttpStatus.OK, null, null);

        String expected = "HTTP/1.1 200 OK\r\n\r\n";
        String actual = out.toString(StandardCharsets.US_ASCII);
        assertEquals(expected, actual);
    }

    private static final List<String> PARSE_REQUEST_BY_PATH_CASES = List.of("/", "/abcdefg");

    @ParameterizedTest(name = "GET {0}")
    @FieldSource("PARSE_REQUEST_BY_PATH_CASES")
    void shouldParseRequestByPath(String path) throws IOException, HttpServer.HttpException {
        InputStream in = createGetRequestStream(path);

        HttpServer.HttpRequest request = HttpServer.parseRequest(in);

        assertEquals("GET", request.method);
        assertEquals(path, request.path);
        assertEquals("HTTP/1.1", request.version);
    }

    private static final List<Arguments> HANDLE_REQUEST_BY_PATH_CASES =
            List.of(
                    Arguments.of("/", "HTTP/1.1 200 OK\r\n\r\n"),
                    Arguments.of("/abcdefg", "HTTP/1.1 404 Not Found\r\n\r\n"));

    @ParameterizedTest(name = "GET {0}")
    @FieldSource("HANDLE_REQUEST_BY_PATH_CASES")
    void shouldHandleRequestByPath(String path, String expectedResponse) throws IOException {
        InputStream in = createGetRequestStream(path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        HttpServer.handleRequest(in, out);

        assertEquals(expectedResponse, out.toString(StandardCharsets.US_ASCII));
    }

    private static InputStream createGetRequestStream(String path) {
        String rawRequest =
                "GET "
                        + path
                        + " HTTP/1.1\r\n"
                        + "Host: localhost:4221\r\n"
                        + "User-Agent: curl/8.7.1\r\n"
                        + "Accept: */*\r\n"
                        + "\r\n";
        return new ByteArrayInputStream(rawRequest.getBytes(StandardCharsets.US_ASCII));
    }
}
