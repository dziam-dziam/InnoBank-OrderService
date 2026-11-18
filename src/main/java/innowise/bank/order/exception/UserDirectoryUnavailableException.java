package innowise.bank.order.exception;

public class UserDirectoryUnavailableException extends RuntimeException {

    public UserDirectoryUnavailableException() {
        super("User Service is unavailable");
    }

    public UserDirectoryUnavailableException(Throwable cause) {
        super("User Service is unavailable", cause);
    }
}
