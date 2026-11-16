import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class HttpServerTest {
    @Test
    void shouldSend200OkResponse() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpServer.sendResponse(out, HttpServer.HttpStatus.OK, null, null);

        String expected = "HTTP/1.1 200 OK\r\n\r\n";
        String actual = out.toString(StandardCharsets.US_ASCII);
        assertEquals(expected, actual);
    }
}
