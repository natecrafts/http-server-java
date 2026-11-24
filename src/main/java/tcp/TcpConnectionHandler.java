package tcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Handles a single TCP connection given the I/O streams.
 * Implementations must be thread-safe as the connections are handled concurrently.
 */
public interface TcpConnectionHandler {
    void handle(InputStream in, OutputStream out) throws IOException;
}
