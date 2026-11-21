package http;

public class HttpResponse {
    public HttpStatus status;
    public String contentType;
    public byte[] body;

    public HttpResponse(HttpStatus status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }
}
