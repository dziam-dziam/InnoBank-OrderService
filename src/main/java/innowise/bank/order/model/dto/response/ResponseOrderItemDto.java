package innowise.bank.order.model.dto.response;

public record ResponseOrderItemDto(
        Long id,
        Long itemId,
        String itemName,
        Integer quantity
) {
}
