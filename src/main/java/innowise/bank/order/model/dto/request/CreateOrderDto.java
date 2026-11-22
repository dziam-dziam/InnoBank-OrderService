package innowise.bank.order.model.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderDto(
        @Positive Long userId,
        @Email @Size(min = 1, max = 255) String email,
        @NotBlank @Size(max = 32) String status,
        @NotEmpty List<@NotNull @Valid OrderLineDto> items
) {
    public CreateOrderDto(Long userId, String status, List<OrderLineDto> items) {
        this(userId, null, status, items);
    }

    @AssertTrue(message = "Provide an email or a user ID")
    @JsonIgnore
    public boolean isUserIdentifierPresent() {
        return email != null || userId != null;
    }
}
