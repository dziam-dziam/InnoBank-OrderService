package innowise.bank.order.service;

import innowise.bank.order.client.UserDirectoryClient;
import innowise.bank.order.exception.ItemNotFoundByIdException;
import innowise.bank.order.exception.OrderNotFoundByIdException;
import innowise.bank.order.exception.UserDirectoryUnavailableException;
import innowise.bank.order.exception.UserNotFoundByEmailException;
import innowise.bank.order.mapper.OrderMapper;
import innowise.bank.order.model.dto.request.CreateOrderDto;
import innowise.bank.order.model.dto.request.OrderLineDto;
import innowise.bank.order.model.dto.request.UpdateOrderDto;
import innowise.bank.order.model.dto.response.ResponseOrderDto;
import innowise.bank.order.model.dto.response.ResponseUserDto;
import innowise.bank.order.model.entity.ItemEntity;
import innowise.bank.order.model.entity.OrderEntity;
import innowise.bank.order.model.entity.OrderItemEntity;
import innowise.bank.order.repository.ItemRepository;
import innowise.bank.order.repository.OrderItemRepository;
import innowise.bank.order.repository.OrderRepository;
import innowise.bank.order.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    private static final long ORDER_ID = 10L;
    private static final long ITEM_ID = 20L;
    private static final long USER_ID = 42L;
    private static final String EMAIL = "jan+orders@example.com";
    private static final ResponseUserDto USER =
            new ResponseUserDto(USER_ID, "Jan", "Kowalski", LocalDate.of(1990, 1, 1), EMAIL, true);

    @Mock private OrderRepository orders;
    @Mock private OrderItemRepository positions;
    @Mock private ItemRepository items;
    @Mock private UserDirectoryClient users;
    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(orders, positions, items, users, Mappers.getMapper(OrderMapper.class));
    }

    @Test
    void shouldCreateOrderByEmailAndCalculateTotalForAllLines() {
        when(users.getByEmail(EMAIL)).thenReturn(USER);
        when(items.findAllById(List.of(ITEM_ID))).thenReturn(List.of(item()));
        assignIdsOnSave();

        var result = service.createOrder(new CreateOrderDto(null, EMAIL, "NEW",
                List.of(new OrderLineDto(ITEM_ID, 2), new OrderLineDto(ITEM_ID, 3))));

        assertThat(result.id()).isEqualTo(ORDER_ID);
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.user()).isEqualTo(USER);
        assertThat(result.totalPrice()).isEqualByComparingTo("62.50");
        assertThat(result.items()).extracting(position -> position.quantity()).containsExactly(2, 3);
        verify(users, never()).getById(any());
    }

    @Test
    void shouldCreateOrderWithExistingUserIdRequest() {
        when(users.getById(USER_ID)).thenReturn(USER);
        when(items.findAllById(List.of(ITEM_ID))).thenReturn(List.of(item()));
        assignIdsOnSave();

        assertThat(service.createOrder(new CreateOrderDto(USER_ID, "NEW", List.of(new OrderLineDto(ITEM_ID, 1))))
                .user()).isEqualTo(USER);
        verify(users, never()).getByEmail(any());
    }

    @Test
    void shouldRejectConflictingUserIdAndEmailBeforeAccessingRepositories() {
        when(users.getByEmail(EMAIL)).thenReturn(USER);

        assertThatThrownBy(() -> service.createOrder(new CreateOrderDto(USER_ID + 1, EMAIL, "NEW",
                List.of(new OrderLineDto(ITEM_ID, 1)))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orders, items, positions);
    }

    @Test
    void shouldRejectOrderForUnknownEmail() {
        when(users.getByEmail(EMAIL)).thenThrow(new UserNotFoundByEmailException());

        assertThatThrownBy(() -> service.createOrder(new CreateOrderDto(null, EMAIL, "NEW",
                List.of(new OrderLineDto(ITEM_ID, 1)))))
                .isInstanceOf(UserNotFoundByEmailException.class);
        verifyNoInteractions(orders, items, positions);
    }

    @Test
    void shouldRejectOrderWithUnknownItem() {
        when(users.getByEmail(EMAIL)).thenReturn(USER);
        when(items.findAllById(List.of(ITEM_ID))).thenReturn(List.of());

        assertThatThrownBy(() -> service.createOrder(new CreateOrderDto(null, EMAIL, "NEW",
                List.of(new OrderLineDto(ITEM_ID, 1)))))
                .isInstanceOf(ItemNotFoundByIdException.class);
        verifyNoInteractions(orders, positions);
    }

    @Test
    void shouldGetOrderDetailsWithUserAndPositions() {
        OrderEntity order = order(ORDER_ID);
        OrderItemEntity position = addPosition(order);
        when(orders.findDetailsById(ORDER_ID)).thenReturn(Optional.of(order));
        when(users.getById(USER_ID)).thenReturn(USER);

        var result = service.getOrder(ORDER_ID);

        assertThat(result.user()).isEqualTo(USER);
        assertThat(result.items()).singleElement().satisfies(dto -> {
            assertThat(dto.id()).isEqualTo(position.getId());
            assertThat(dto.itemName()).isEqualTo("Notebook");
            assertThat(dto.quantity()).isEqualTo(1);
        });
    }

    @Test
    void shouldRejectReadOfMissingOrderWithoutUserLookup() {
        when(orders.findDetailsById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrder(ORDER_ID)).isInstanceOf(OrderNotFoundByIdException.class);
        verifyNoInteractions(users);
    }

    @Test
    void shouldGetAllOrdersWithoutFilters() {
        PageRequest pageable = PageRequest.of(1, 2);
        stubOrderPage(pageable, List.of(order(ORDER_ID)), 3);
        when(users.getById(USER_ID)).thenReturn(USER);

        var result = service.getAllOrders(pageable);

        assertThat(result.getPageable()).isEqualTo(pageable);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.id()).isEqualTo(ORDER_ID);
            assertThat(dto.user()).isEqualTo(USER);
        });
    }

    @Test
    void shouldGetFilteredPageAndLookupSameUserOnce() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        PageRequest pageable = PageRequest.of(0, 10);
        stubOrderPage(pageable, List.of(order(ORDER_ID), order(ORDER_ID + 1)), 2);
        when(users.getById(USER_ID)).thenReturn(USER);

        var result = service.getAllOrders(from, from.plusSeconds(60), List.of("NEW"), pageable);

        assertThat(result.getContent()).extracting(ResponseOrderDto::user).containsExactly(USER, USER);
        verify(users).getById(USER_ID);
    }

    @Test
    void shouldRejectInvertedDateRangeBeforeSearching() {
        Instant to = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> service.getAllOrders(to.plusSeconds(1), to, List.of("NEW"), PageRequest.of(0, 10)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orders, users);
    }

    @Test
    void shouldListOrdersForOneUserInRepositoryOrder() {
        when(orders.findAllByUserIdOrderByIdAsc(USER_ID)).thenReturn(List.of(order(ORDER_ID), order(ORDER_ID + 1)));
        when(users.getById(USER_ID)).thenReturn(USER);

        assertThat(service.getOrdersByUserId(USER_ID)).extracting(ResponseOrderDto::id)
                .containsExactly(ORDER_ID, ORDER_ID + 1);
        verify(users).getById(USER_ID);
    }

    @Test
    void shouldReturnEmptyListWithoutCallingUserService() {
        when(orders.findAllByUserIdOrderByIdAsc(USER_ID)).thenReturn(List.of());

        assertThat(service.getOrdersByUserId(USER_ID)).isEmpty();
        verifyNoInteractions(users);
    }

    @Test
    void shouldUpdateStatusWithoutReplacingPositionsWhenItemsAreOmitted() {
        OrderEntity order = order(ORDER_ID);
        OrderItemEntity original = addPosition(order);
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(users.getById(USER_ID)).thenReturn(USER);

        var result = service.updateOrder(ORDER_ID, new UpdateOrderDto("PAID", null));

        assertThat(result.status()).isEqualTo("PAID");
        assertThat(order.getOrderItems()).containsExactly(original);
        assertThat(result.totalPrice()).isEqualByComparingTo("12.50");
        verifyNoInteractions(positions, items);
        verify(orders).flush();
    }

    @Test
    void shouldReplacePositionsAndRecalculatePriceOnUpdate() {
        OrderEntity order = order(ORDER_ID);
        OrderItemEntity original = addPosition(order);
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(users.getById(USER_ID)).thenReturn(USER);
        when(items.findAllById(List.of(ITEM_ID))).thenReturn(List.of(item()));
        doAnswer(invocation -> {
            Iterable<OrderItemEntity> deleted = invocation.getArgument(0);
            assertThat(deleted).containsExactly(original);
            return null;
        }).when(positions).deleteAll(any());
        doAnswer(invocation -> {
            order.getOrderItems().getFirst().setId(102L);
            return null;
        }).when(orders).flush();

        var result = service.updateOrder(ORDER_ID, new UpdateOrderDto("PAID", List.of(new OrderLineDto(ITEM_ID, 3))));

        assertThat(result.status()).isEqualTo("PAID");
        assertThat(result.totalPrice()).isEqualByComparingTo("37.50");
        assertThat(result.items()).singleElement().satisfies(dto -> {
            assertThat(dto.id()).isEqualTo(102L);
            assertThat(dto.quantity()).isEqualTo(3);
        });
        verify(positions).deleteAll(any());
    }

    @Test
    void shouldRejectUpdateOfMissingOrderWithoutUserLookup() {
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateOrder(ORDER_ID, new UpdateOrderDto("PAID", null)))
                .isInstanceOf(OrderNotFoundByIdException.class);
        verifyNoInteractions(users);
    }

    @Test
    void shouldLeaveOrderUnchangedWhenUserLookupFailsOnUpdate() {
        OrderEntity order = order(ORDER_ID);
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(users.getById(USER_ID)).thenThrow(new UserDirectoryUnavailableException());

        assertThatThrownBy(() -> service.updateOrder(ORDER_ID, new UpdateOrderDto("PAID", null)))
                .isInstanceOf(UserDirectoryUnavailableException.class);
        assertThat(order.getStatus()).isEqualTo("NEW");
        verify(orders, never()).flush();
    }

    @Test
    void shouldSoftDeleteLockedOrderWithoutCallingUserService() {
        OrderEntity order = order(ORDER_ID);
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        service.deleteOrder(ORDER_ID);

        assertThat(order.isDeleted()).isTrue();
        verifyNoInteractions(positions, items, users);
    }

    @Test
    void shouldRejectDeletionOfMissingOrder() {
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteOrder(ORDER_ID)).isInstanceOf(OrderNotFoundByIdException.class);
        verifyNoInteractions(users);
    }

    private void assignIdsOnSave() {
        when(orders.saveAndFlush(any(OrderEntity.class))).thenAnswer(invocation -> {
            OrderEntity order = invocation.getArgument(0);
            order.setId(ORDER_ID);
            long positionId = 101L;
            for (OrderItemEntity position : order.getOrderItems()) {
                position.setId(positionId++);
            }
            return order;
        });
    }

    private void stubOrderPage(PageRequest pageable, List<OrderEntity> content, long total) {
        when(orders.findAll(ArgumentMatchers.<Specification<OrderEntity>>any(), eq(pageable)))
                .thenReturn(new PageImpl<>(content, pageable, total));
    }

    private OrderEntity order(long id) {
        OrderEntity order = new OrderEntity();
        order.setId(id);
        order.setUserId(USER_ID);
        order.setStatus("NEW");
        order.setTotalPrice(new BigDecimal("12.50"));
        return order;
    }

    private ItemEntity item() {
        ItemEntity item = new ItemEntity();
        item.setId(ITEM_ID);
        item.setName("Notebook");
        item.setPrice(new BigDecimal("12.50"));
        return item;
    }

    private OrderItemEntity addPosition(OrderEntity order) {
        OrderItemEntity position = new OrderItemEntity();
        position.setId(101L);
        position.setItem(item());
        position.setQuantity(1);
        order.addOrderItem(position);
        return position;
    }
}
