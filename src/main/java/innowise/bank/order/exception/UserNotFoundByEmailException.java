package innowise.bank.order.exception;

public class UserNotFoundByEmailException extends RuntimeException {

    public UserNotFoundByEmailException() {
        super("User with the given email was not found");
    }
}
