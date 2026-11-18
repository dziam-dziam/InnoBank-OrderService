package innowise.bank.order.service.impl;


import innowise.bank.order.exception.ItemInUseException;
import innowise.bank.order.exception.ItemNotFoundByIdException;
import innowise.bank.order.mapper.ItemMapper;
import innowise.bank.order.model.dto.request.CreateItemDto;
import innowise.bank.order.model.dto.request.UpdateItemDto;
import innowise.bank.order.model.dto.response.ResponseItemDto;
import innowise.bank.order.model.entity.ItemEntity;
import innowise.bank.order.repository.ItemRepository;
import innowise.bank.order.service.ItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(value = "transactionManager", readOnly = true)
public class ItemServiceImpl implements ItemService {

    private final ItemRepository itemRepository;
    private final ItemMapper itemMapper;

    @Override
    @Transactional("transactionManager")
    public ResponseItemDto createItem(CreateItemDto dto) {
        ItemEntity item = itemMapper.toEntity(dto);

        return itemMapper.toDto(
                itemRepository.saveAndFlush(item)
        );
    }

    @Override
    public ResponseItemDto getItem(Long id) {
        ItemEntity item = itemRepository.findById(id)
                .orElseThrow(() -> new ItemNotFoundByIdException(id));

        return itemMapper.toDto(item);
    }

    @Override
    public Page<ResponseItemDto> getAllItems(Pageable pageable) {
        return itemRepository.findAll(pageable)
                .map(itemMapper::toDto);
    }

    @Override
    @Transactional("transactionManager")
    public ResponseItemDto updateItem(Long id, UpdateItemDto dto) {
        ItemEntity item = findForUpdate(id);
        itemMapper.updateEntity(dto, item);

        return itemMapper.toDto(item);
    }

    @Override
    @Transactional("transactionManager")
    public void deleteItem(Long id) {
        ItemEntity item = findForUpdate(id);

        if (itemRepository.existsByIdAndOrderItemsIsNotEmpty(id)) {
            throw new ItemInUseException(id);
        }

        itemRepository.delete(item);
        itemRepository.flush();
    }

    private ItemEntity findForUpdate(Long id) {
        return itemRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ItemNotFoundByIdException(id));
    }
}