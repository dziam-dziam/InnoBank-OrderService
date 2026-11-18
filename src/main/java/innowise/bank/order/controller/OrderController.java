package innowise.bank.order.controller;


import innowise.bank.order.model.dto.response.ResponseOrderDetailsDto;
import innowise.bank.order.model.dto.response.ResponseOrderDto;
import innowise.bank.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public ResponseEntity<Page<ResponseOrderDto>> getAllOrders(
            @PageableDefault(size = 20, sort = "id") Pageable pageable
    ) {
        return ResponseEntity.ok(orderService.getAllOrders(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResponseOrderDetailsDto> getOrder(
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(orderService.getOrder(id));
    }
}