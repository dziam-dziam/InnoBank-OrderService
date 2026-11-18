package innowise.bank.order.repository;


import innowise.bank.order.model.entity.OrderItemEntity;
import org.springframework.data.repository.Repository;

public interface OrderItemRepository extends Repository<OrderItemEntity, Long> {

    void deleteAll(Iterable<? extends OrderItemEntity> entities);
}
