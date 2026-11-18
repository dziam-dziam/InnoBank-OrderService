package innowise.bank.order.model.dto.response;

import java.math.BigDecimal;

public record ResponseItemDto(
        Long id,
        String name,
        BigDecimal price
) {
}