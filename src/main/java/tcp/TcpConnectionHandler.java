package tcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface TcpConnectionHandler {
    void handle(InputStream in, OutputStream out) throws IOException;
}
