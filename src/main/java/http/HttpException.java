package http;

public class HttpException extends Exception {
    public final String message;

    public HttpException(String message) {
        this.message = message;
    }
}
