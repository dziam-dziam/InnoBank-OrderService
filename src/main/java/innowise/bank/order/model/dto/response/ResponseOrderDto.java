package innowise.bank.order.model.dto.response;

import java.math.BigDecimal;

public record ResponseOrderDto(
        Long id,
        Long userId,
        String status,
        BigDecimal totalPrice,
        boolean deleted,
        ResponseUserDto user
) {
}
