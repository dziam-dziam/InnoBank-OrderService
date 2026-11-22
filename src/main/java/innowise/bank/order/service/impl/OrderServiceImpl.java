package innowise.bank.order.service.impl;


import innowise.bank.order.client.UserDirectoryClient;
import innowise.bank.order.exception.ItemNotFoundByIdException;
import innowise.bank.order.exception.OrderNotFoundByIdException;
import innowise.bank.order.mapper.OrderMapper;
import innowise.bank.order.model.dto.request.CreateOrderDto;
import innowise.bank.order.model.dto.request.OrderLineDto;
import innowise.bank.order.model.dto.request.UpdateOrderDto;
import innowise.bank.order.model.dto.response.ResponseOrderDetailsDto;
import innowise.bank.order.model.dto.response.ResponseOrderDto;
import innowise.bank.order.model.dto.response.ResponseUserDto;
import innowise.bank.order.model.entity.ItemEntity;
import innowise.bank.order.model.entity.OrderEntity;
import innowise.bank.order.model.entity.OrderItemEntity;
import innowise.bank.order.repository.ItemRepository;
import innowise.bank.order.repository.OrderItemRepository;
import innowise.bank.order.repository.OrderRepository;
import innowise.bank.order.service.OrderService;
import innowise.bank.order.specification.OrderSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Validated
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(value = "transactionManager", readOnly = true)
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ItemRepository itemRepository;
    private final UserDirectoryClient users;
    private final OrderMapper orderMapper;

    @Override
    @Transactional("transactionManager")
    public ResponseOrderDetailsDto createOrder(CreateOrderDto dto) {
        ResponseUserDto user = dto.email() == null ? users.getById(dto.userId()) : users.getByEmail(dto.email());
        if (dto.userId() != null && !dto.userId().equals(user.id())) {
            throw new IllegalArgumentException("User ID and email refer to different users");
        }

        OrderEntity order = orderMapper.toEntity(dto, user);
        addOrderItems(order, dto.items());

        OrderEntity savedOrder = orderRepository.saveAndFlush(order);
        return orderMapper.toDetailsDto(savedOrder, user);
    }

    @Override
    public ResponseOrderDetailsDto getOrder(Long id) {
        OrderEntity order = orderRepository.findDetailsById(id)
                .orElseThrow(() -> new OrderNotFoundByIdException(id));

        return orderMapper.toDetailsDto(order, users.getById(order.getUserId()));
    }

    @Override
    public Page<ResponseOrderDto> getAllOrders(Pageable pageable) {
        return getAllOrders(null, null, null, pageable);
    }

    @Override
    public Page<ResponseOrderDto> getAllOrders(
            Instant createdFrom, Instant createdTo, Collection<String> statuses, Pageable pageable
    ) {
        Map<Long, ResponseUserDto> usersById = new HashMap<>();
        return orderRepository.findAll(OrderSpecifications.filter(createdFrom, createdTo, statuses), pageable)
                .map(order -> orderMapper.toDto(order, usersById.computeIfAbsent(order.getUserId(), users::getById)));
    }

    @Override
    public List<ResponseOrderDto> getOrdersByUserId(Long userId) {
        List<OrderEntity> orders = orderRepository.findAllByUserIdOrderByIdAsc(userId);
        if (orders.isEmpty()) {
            return List.of();
        }

        ResponseUserDto user = users.getById(userId);
        return orders.stream()
                .map(order -> orderMapper.toDto(order, user))
                .toList();
    }

    @Override
    @Transactional("transactionManager")
    public ResponseOrderDetailsDto updateOrder(Long id, UpdateOrderDto dto) {
        OrderEntity order = findForUpdate(id);
        ResponseUserDto user = users.getById(order.getUserId());
        orderMapper.updateEntity(dto, order);

        if (dto.items() != null) {
            orderItemRepository.deleteAll(order.getOrderItems());
            order.getOrderItems().clear();
            addOrderItems(order, dto.items());
        }

        orderRepository.flush();
        return orderMapper.toDetailsDto(order, user);
    }

    @Override
    @Transactional("transactionManager")
    public void deleteOrder(Long id) {
        OrderEntity order = findForUpdate(id);
        order.setDeleted(true);
    }

    private OrderEntity findForUpdate(Long id) {
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new OrderNotFoundByIdException(id));
    }

    private void addOrderItems(OrderEntity order, List<OrderLineDto> lines) {
        List<Long> itemIds = lines.stream().map(OrderLineDto::itemId).distinct().toList();
        Map<Long, ItemEntity> itemsById = new HashMap<>();

        for (ItemEntity item : itemRepository.findAllById(itemIds)) {
            itemsById.put(item.getId(), item);
        }

        BigDecimal totalPrice = BigDecimal.ZERO;
        for (OrderLineDto line : lines) {
            ItemEntity item = itemsById.get(line.itemId());
            if (item == null) {
                throw new ItemNotFoundByIdException(line.itemId());
            }

            OrderItemEntity position = orderMapper.toEntity(line, item);
            order.addOrderItem(position);

            BigDecimal linePrice = item.getPrice().multiply(BigDecimal.valueOf(line.quantity()));
            totalPrice = totalPrice.add(linePrice);
        }

        order.setTotalPrice(totalPrice);
    }
}
