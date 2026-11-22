package innowise.bank.order.mapper;


import innowise.bank.order.model.dto.request.CreateOrderDto;
import innowise.bank.order.model.dto.request.OrderLineDto;
import innowise.bank.order.model.dto.request.UpdateOrderDto;
import innowise.bank.order.model.dto.response.ResponseOrderDetailsDto;
import innowise.bank.order.model.dto.response.ResponseOrderDto;
import innowise.bank.order.model.dto.response.ResponseOrderItemDto;
import innowise.bank.order.model.dto.response.ResponseUserDto;
import innowise.bank.order.model.entity.ItemEntity;
import innowise.bank.order.model.entity.OrderEntity;
import innowise.bank.order.model.entity.OrderItemEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import org.mapstruct.BeanMapping;
import org.mapstruct.MappingTarget;
import java.util.Comparator;
import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OrderMapper {

    @Mapping(target = "id", source = "entity.id")
    @Mapping(target = "user", source = "user")
    ResponseOrderDto toDto(OrderEntity entity, ResponseUserDto user);

    @Mapping(target = "id", source = "entity.id")
    @Mapping(target = "items", source = "entity.orderItems")
    @Mapping(target = "user", source = "user")
    ResponseOrderDetailsDto toDetailsDto(OrderEntity entity, ResponseUserDto user);

    @Mapping(target = "itemId", source = "item.id")
    @Mapping(target = "itemName", source = "item.name")
    ResponseOrderItemDto toDto(OrderItemEntity entity);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "status", source = "dto.status")
    @Mapping(target = "userId", source = "user.id")
    OrderEntity toEntity(CreateOrderDto dto, ResponseUserDto user);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "quantity", source = "line.quantity")
    @Mapping(target = "item", source = "item")
    OrderItemEntity toEntity(OrderLineDto line, ItemEntity item);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "status", source = "status")
    OrderEntity updateEntity(UpdateOrderDto dto, @MappingTarget OrderEntity order);

    default List<ResponseOrderItemDto> mapOrderItems(
            List<OrderItemEntity> entities
    ) {
        if (entities == null) {
            return List.of();
        }

        return entities.stream()
                .sorted(Comparator.comparing(OrderItemEntity::getId))
                .map(this::toDto)
                .toList();
    }
}
