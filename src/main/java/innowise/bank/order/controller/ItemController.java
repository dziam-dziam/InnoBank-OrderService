package innowise.bank.order.controller;


import innowise.bank.order.model.dto.request.CreateItemDto;
import innowise.bank.order.model.dto.request.UpdateItemDto;
import innowise.bank.order.model.dto.response.ResponseItemDto;
import innowise.bank.order.service.ItemService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/items")
public class ItemController {

    private final ItemService itemService;

    @PostMapping
    public ResponseEntity<ResponseItemDto> createItem(@Valid @RequestBody CreateItemDto dto) {
        ResponseItemDto item = itemService.createItem(dto);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(item.id()).toUri();
        return ResponseEntity.created(location).body(item);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResponseItemDto> getItem(@PathVariable Long id) {
        return ResponseEntity.ok(itemService.getItem(id));
    }

    @GetMapping
    public ResponseEntity<Page<ResponseItemDto>> getAllItems(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return ResponseEntity.ok(itemService.getAllItems(pageable));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ResponseItemDto> updateItem(@PathVariable Long id, @Valid @RequestBody UpdateItemDto dto) {
        return ResponseEntity.ok(itemService.updateItem(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteItem(@PathVariable Long id) {
        itemService.deleteItem(id);

        return ResponseEntity.noContent().build();
    }
}
