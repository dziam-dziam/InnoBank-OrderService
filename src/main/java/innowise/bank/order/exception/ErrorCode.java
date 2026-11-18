package innowise.bank.order.exception;

import lombok.Getter;

public enum ErrorCode {

    ITEM_NOT_FOUND(
            "ERR_ITEM_404",
            "ItemEntity with id: %d was not found"
    ),

    ORDER_NOT_FOUND(
            "ERR_ORDER_404",
            "OrderEntity with id: %d was not found"
    ),

    ITEM_IN_USE(
            "ERR_ITEM_IN_USE",
            "ItemEntity with id: %d is used in an order and cannot be deleted"
    ),

    USER_NOT_FOUND(
            "ERR_USER_404",
            "UserEntity with id: %d was not found"
    );

    @Getter
    private final String errorName;
    private final String message;

    ErrorCode(String errorName, String message) {
        this.errorName = errorName;
        this.message = message;
    }

    public String formatMessage(Object... args) {
        return String.format(message, args);
    }
}
