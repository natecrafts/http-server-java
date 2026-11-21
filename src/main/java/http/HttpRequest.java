package http;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

public class HttpRequest {
    public String method;
    public String path;
    public String version;
    public Map<String, String> headers;
    public byte[] body;

    public HttpRequest(String method, String path, String version, Map<String, String> headers) {
        this(method, path, version, headers, null);
    }

    public HttpRequest(
            String method, String path, String version, Map<String, String> headers, byte[] body) {
        this.method = method;
        this.path = path;
        this.version = version;
        this.headers = headers;
        this.body = body;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof HttpRequest other)) return false;
        return Objects.equals(method, other.method)
                && Objects.equals(path, other.path)
                && Objects.equals(version, other.version)
                && Objects.equals(headers, other.headers)
                && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, path, version, headers);
    }

    @Override
    public String toString() {
        return "HttpRequest{"
                + "method='"
                + method
                + '\''
                + ", path='"
                + path
                + '\''
                + ", version='"
                + version
                + '\''
                + ", headers="
                + headers
                + '}';
    }
}
