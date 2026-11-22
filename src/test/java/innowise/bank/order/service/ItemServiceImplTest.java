package innowise.bank.order.service;

import innowise.bank.order.exception.ItemInUseException;
import innowise.bank.order.exception.ItemNotFoundByIdException;
import innowise.bank.order.mapper.ItemMapper;
import innowise.bank.order.model.dto.request.CreateItemDto;
import innowise.bank.order.model.dto.request.UpdateItemDto;
import innowise.bank.order.model.dto.response.ResponseItemDto;
import innowise.bank.order.model.entity.ItemEntity;
import innowise.bank.order.repository.ItemRepository;
import innowise.bank.order.service.impl.ItemServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemServiceImplTest {

    private static final long ITEM_ID = 7L;
    private static final BigDecimal PRICE = new BigDecimal("12.50");

    @Mock private ItemRepository repository;
    private ItemService service;

    @BeforeEach
    void setUp() {
        service = new ItemServiceImpl(repository, Mappers.getMapper(ItemMapper.class));
    }

    @Test
    void shouldCreateItemFromDtoAndReturnSavedItem() {
        when(repository.saveAndFlush(any(ItemEntity.class))).thenAnswer(invocation -> {
            ItemEntity item = invocation.getArgument(0);
            assertThat(item.getName()).isEqualTo("Notebook");
            assertThat(item.getPrice()).isEqualByComparingTo(PRICE);
            item.setId(ITEM_ID);
            return item;
        });

        assertThat(service.createItem(new CreateItemDto("Notebook", PRICE)))
                .isEqualTo(new ResponseItemDto(ITEM_ID, "Notebook", PRICE));
    }

    @Test
    void shouldGetItemById() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.of(item()));

        assertThat(service.getItem(ITEM_ID)).isEqualTo(new ResponseItemDto(ITEM_ID, "Notebook", PRICE));
    }

    @Test
    void shouldRejectReadOfMissingItem() {
        when(repository.findById(ITEM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getItem(ITEM_ID)).isInstanceOf(ItemNotFoundByIdException.class);
    }

    @Test
    void shouldMapAllItemsAndPreservePagination() {
        PageRequest pageable = PageRequest.of(1, 2);
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(item()), pageable, 3));

        var page = service.getAllItems(pageable);

        assertThat(page.getContent()).containsExactly(new ResponseItemDto(ITEM_ID, "Notebook", PRICE));
        assertThat(page.getPageable()).isEqualTo(pageable);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void shouldUpdateLockedItem() {
        ItemEntity item = item();
        BigDecimal updatedPrice = new BigDecimal("19.99");
        when(repository.findByIdForUpdate(ITEM_ID)).thenReturn(Optional.of(item));

        assertThat(service.updateItem(ITEM_ID, new UpdateItemDto("Planner", updatedPrice)))
                .isEqualTo(new ResponseItemDto(ITEM_ID, "Planner", updatedPrice));
        assertThat(item.getName()).isEqualTo("Planner");
        assertThat(item.getPrice()).isEqualByComparingTo(updatedPrice);
    }

    @Test
    void shouldRejectUpdateOfMissingItem() {
        when(repository.findByIdForUpdate(ITEM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateItem(ITEM_ID, new UpdateItemDto("Planner", PRICE)))
                .isInstanceOf(ItemNotFoundByIdException.class);
    }

    @Test
    void shouldDeleteUnusedItemAndFlushRepository() {
        ItemEntity item = item();
        when(repository.findByIdForUpdate(ITEM_ID)).thenReturn(Optional.of(item));
        when(repository.existsByIdAndOrderItemsIsNotEmpty(ITEM_ID)).thenReturn(false);

        service.deleteItem(ITEM_ID);

        InOrder operations = inOrder(repository);
        operations.verify(repository).findByIdForUpdate(ITEM_ID);
        operations.verify(repository).existsByIdAndOrderItemsIsNotEmpty(ITEM_ID);
        operations.verify(repository).delete(item);
        operations.verify(repository).flush();
    }

    @Test
    void shouldRejectDeletionOfItemUsedByOrder() {
        when(repository.findByIdForUpdate(ITEM_ID)).thenReturn(Optional.of(item()));
        when(repository.existsByIdAndOrderItemsIsNotEmpty(ITEM_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteItem(ITEM_ID)).isInstanceOf(ItemInUseException.class);
        verify(repository, never()).delete(any());
        verify(repository, never()).flush();
    }

    @Test
    void shouldRejectDeletionOfMissingItem() {
        when(repository.findByIdForUpdate(ITEM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteItem(ITEM_ID)).isInstanceOf(ItemNotFoundByIdException.class);
        verify(repository, never()).delete(any());
    }

    private ItemEntity item() {
        ItemEntity item = new ItemEntity();
        item.setId(ITEM_ID);
        item.setName("Notebook");
        item.setPrice(PRICE);
        return item;
    }
}
