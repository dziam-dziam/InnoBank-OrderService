package innowise.bank.order.controller;


import innowise.bank.order.model.dto.request.CreateOrderDto;
import innowise.bank.order.model.dto.request.UpdateOrderDto;
import innowise.bank.order.model.dto.response.ResponseOrderDetailsDto;
import innowise.bank.order.model.dto.response.ResponseOrderDto;
import innowise.bank.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<ResponseOrderDetailsDto> createOrder(@Valid @RequestBody CreateOrderDto dto) {
        ResponseOrderDetailsDto order = orderService.createOrder(dto);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(order.id()).toUri();
        return ResponseEntity.created(location).body(order);
    }

    @GetMapping
    public ResponseEntity<Page<ResponseOrderDto>> getAllOrders(
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(required = false) List<String> statuses,
            @PageableDefault(size = 20, sort = "id") Pageable pageable
    ) {
        return ResponseEntity.ok(orderService.getAllOrders(createdFrom, createdTo, statuses, pageable));
    }

    @GetMapping("/by-user/{userId}")
    public ResponseEntity<List<ResponseOrderDto>> getOrdersByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(orderService.getOrdersByUserId(userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResponseOrderDetailsDto> getOrder(
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(orderService.getOrder(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ResponseOrderDetailsDto> updateOrder(
            @PathVariable Long id, @Valid @RequestBody UpdateOrderDto dto
    ) {
        return ResponseEntity.ok(orderService.updateOrder(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        orderService.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }
}
