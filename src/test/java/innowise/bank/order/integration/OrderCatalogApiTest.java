package innowise.bank.order.integration;


import innowise.bank.order.client.UserDirectoryClient;
import innowise.bank.order.exception.UserDirectoryUnavailableException;
import innowise.bank.order.model.dto.request.CreateItemDto;
import innowise.bank.order.model.dto.response.ResponseItemDto;
import innowise.bank.order.model.dto.response.ResponseOrderDetailsDto;
import innowise.bank.order.model.dto.response.ResponseUserDto;
import innowise.bank.order.model.entity.ItemEntity;
import innowise.bank.order.model.entity.OrderEntity;
import innowise.bank.order.model.entity.OrderItemEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.jpa.open-in-view=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
@Transactional
class OrderCatalogApiTest {

    private static final String OWNER_EMAIL = "jan+orders@example.com";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private UserDirectoryClient users;
    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUpUserDirectory() {
        ResponseUserDto owner = new ResponseUserDto(owner(), "Jan", "Kowalski", LocalDate.of(1990, 1, 1),
                OWNER_EMAIL, true);
        when(users.getById(owner())).thenReturn(owner);
        when(users.getByEmail(OWNER_EMAIL)).thenReturn(owner);
    }

    @Test
    void shouldCreateOrderByEmailAndReturnUserOnUpdate() throws Exception {
        ItemEntity item = item("Notebook", "12.50");
        String response = mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","status":"NEW","items":[{"itemId":%d,"quantity":2}]}
                                """.formatted(OWNER_EMAIL, item.getId())))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/orders/")))
                .andExpect(jsonPath("$.userId").value(owner()))
                .andExpect(jsonPath("$.user.email").value(OWNER_EMAIL))
                .andExpect(jsonPath("$.totalPrice").value(25))
                .andReturn().getResponse().getContentAsString();
        Long id = mapper.readValue(response, ResponseOrderDetailsDto.class).id();
        flushAndClear();

        mvc.perform(put("/api/orders/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PAID","items":[{"itemId":%d,"quantity":3}]}
                                """.formatted(item.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(OWNER_EMAIL))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalPrice").value(37.50));
        flushAndClear();

        clearInvocations(users);
        mvc.perform(delete("/api/orders/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/orders/{id}", id)).andExpect(status().isNotFound());
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\"email\":\"invalid\",", "\"email\":\"\","})
    void shouldRejectInvalidOrderUserIdentifier(String fields) throws Exception {
        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {%s"status":"NEW","items":[{"itemId":1,"quantity":1}]}
                                """.formatted(fields)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }

    @Test
    void shouldReturnServiceUnavailableAndKeepOrderWhenDirectoryFailsDuringUpdate() throws Exception {
        OrderEntity order = order(owner(), "NEW", false);
        flushAndClear();
        when(users.getById(owner())).thenThrow(new UserDirectoryUnavailableException());

        mvc.perform(put("/api/orders/{id}", order.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PAID"}
                                """))
                .andExpect(status().isServiceUnavailable());
        flushAndClear();
        assertThat(entityManager.find(OrderEntity.class, order.getId()).getStatus()).isEqualTo("NEW");
    }

    @Test
    void shouldCreateUpdateAndDeleteUnusedCatalogItem() throws Exception {
        String response = mvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateItemDto("Free sample", BigDecimal.ZERO))))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/items/")))
                .andExpect(jsonPath("$.name").value("Free sample"))
                .andExpect(jsonPath("$.price").value(0))
                .andReturn().getResponse().getContentAsString();
        Long id = mapper.readValue(response, ResponseItemDto.class).id();

        mvc.perform(put("/api/items/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Notebook","price":12.50}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        flushAndClear();
        mvc.perform(get("/api/items/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Notebook"))
                .andExpect(jsonPath("$.price").value(12.50));

        mvc.perform(delete("/api/items/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/items/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void shouldPageAndSortCatalogItems() throws Exception {
        ItemEntity cheaper = item("Pen", "2.00");
        ItemEntity costlier = item("Book", "12.00");
        flushAndClear();

        mvc.perform(get("/api/items").param("size", "1").param("sort", "price,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(costlier.getId()));
        mvc.perform(get("/api/items").param("size", "1").param("page", "1").param("sort", "price,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(cheaper.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", """
            {"name":"Item","price":-1}
            """, """
            {"name":"Item","price":1.234}
            """})
    void shouldRejectInvalidCatalogDataWithoutWritingChanges(String body) throws Exception {
        ItemEntity existing = item("Original", "5.00");
        flushAndClear();

        mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/items/{id}", existing.getId()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(entityManager.find(ItemEntity.class, existing.getId()).getName()).isEqualTo("Original");
        assertThat(entityManager.createQuery("select count(i) from ItemEntity i", Long.class).getSingleResult())
                .isEqualTo(1L);
    }

    @Test
    void shouldReturnNotFoundForMissingOrdersAndItems() throws Exception {
        mvc.perform(get("/api/orders/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/items/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(put("/api/items/{id}", Long.MAX_VALUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Item","price":1.00}
                                """))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/items/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void shouldListOrdersFromDifferentUsersAndHideDeletedOrders() throws Exception {
        // given
        OrderEntity first = order(owner(), "NEW", false);
        OrderEntity deleted = order(owner(), "CANCELLED", true);
        OrderEntity second = order(43L, "PROCESSING", false);
        when(users.getById(second.getUserId())).thenReturn(new ResponseUserDto(second.getUserId(), "Anna",
                "Nowak", LocalDate.of(1991, 1, 1), "anna@example.com", true));
        flushAndClear();

        // when / then
        mvc.perform(get("/api/orders").param("size", "1").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(first.getId()))
                .andExpect(jsonPath("$.content[0].userId").value(first.getUserId()))
                .andExpect(jsonPath("$.content[0].user.email").value(OWNER_EMAIL));

        mvc.perform(get("/api/orders").param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(second.getId()))
                .andExpect(jsonPath("$.content[0].userId").value(second.getUserId()))
                .andExpect(jsonPath("$.content[0].user.email").value("anna@example.com"));

        mvc.perform(get("/api/orders/{id}", deleted.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldFilterOrdersByStatusesAndInclusiveCreationDates() throws Exception {
        OrderEntity first = order(owner(), "NEW", false);
        OrderEntity second = order(owner(), "PAID", false);
        OrderEntity old = order(owner(), "NEW", false);
        OrderEntity otherStatus = order(owner(), "CANCELLED", false);
        OrderEntity deleted = order(owner(), "PAID", true);
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = from.plusSeconds(60);
        setCreatedAt(first, from);
        setCreatedAt(second, to);
        setCreatedAt(old, from.minusSeconds(1));
        setCreatedAt(otherStatus, from);
        setCreatedAt(deleted, to);
        flushAndClear();

        mvc.perform(get("/api/orders").param("createdFrom", from.toString()).param("createdTo", to.toString())
                        .param("statuses", "NEW", "PAID").param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second.getId()))
                .andExpect(jsonPath("$.content[1].id").value(first.getId()))
                .andExpect(jsonPath("$.content[1].user.email").value(OWNER_EMAIL));
    }

    @Test
    void shouldListOnlyActiveOrdersForRequestedUserAndReturnEmptyListWithoutOrders() throws Exception {
        OrderEntity first = order(owner(), "NEW", false);
        OrderEntity second = order(owner(), "PAID", false);
        order(owner(), "CANCELLED", true);
        order(43L, "PAID", false);
        flushAndClear();

        mvc.perform(get("/api/orders/by-user/{userId}", owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first.getId()))
                .andExpect(jsonPath("$[0].user.email").value(OWNER_EMAIL))
                .andExpect(jsonPath("$[1].id").value(second.getId()));

        clearInvocations(users);
        mvc.perform(get("/api/orders/by-user/{userId}", 100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        verifyNoInteractions(users);
    }

    @Test
    void shouldReturnValidationErrorsAsProblemDetails() throws Exception {
        mvc.perform(get("/api/orders/by-user/{userId}", 0))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value(endsWith(".userId")));

        mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"","price":12.00}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void shouldReturnBadRequestForInvalidOrderFilters() throws Exception {
        mvc.perform(get("/api/orders").param("createdFrom", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/orders").param("createdFrom", "2026-01-02T00:00:00Z")
                        .param("createdTo", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").param("statuses", "PAID", " "))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders", "/api/items"})
    void shouldReturnBadRequestForInvalidSort(String path) throws Exception {
        mvc.perform(get(path).param("sort", "unknownProperty,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void shouldHideUnexpectedFailureDetailsFromClient() throws Exception {
        OrderEntity order = order(owner(), "NEW", false);
        flushAndClear();
        when(users.getById(owner())).thenThrow(new IllegalStateException("Internal diagnostic"));

        mvc.perform(get("/api/orders/{id}", order.getId()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }

    @Test
    void shouldReadOrderDetailsWithItemsSortedByPositionId() throws Exception {
        OrderEntity order = order(owner(), "NEW", false);
        OrderItemEntity first = line(order, item("Book", "10.00"), 2);
        OrderItemEntity second = line(order, item("Pen", "5.00"), 1);
        flushAndClear();

        mvc.perform(get("/api/orders/{id}", order.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(order.getUserId()))
                .andExpect(jsonPath("$.user.email").value(OWNER_EMAIL))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.totalPrice").value(25))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(first.getId()))
                .andExpect(jsonPath("$.items[0].itemId").value(first.getItem().getId()))
                .andExpect(jsonPath("$.items[0].itemName").value("Book"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[1].id").value(second.getId()));
    }

    @Test
    void shouldRejectDeletionOfItemReferencedByAnOrder() throws Exception {
        OrderEntity order = order(owner(), "NEW", false);
        ItemEntity item = item("Book", "12.50");
        line(order, item, 2);
        flushAndClear();

        mvc.perform(delete("/api/items/{id}", item.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        assertThat(entityManager.find(ItemEntity.class, item.getId())).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders", "/api/orders/1", "/api/orders/by-user/1", "/api/items", "/api/items/1"})
    void shouldRejectAnonymousAndNonAdminRequests(String path) throws Exception {
        mvc.perform(get(path).with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(user("customer").roles("USER"))).andExpect(status().isForbidden());
    }

    private Long owner() {
        return 42L;
    }

    private ItemEntity item(String name, String price) {
        ItemEntity item = new ItemEntity();
        item.setName(name);
        item.setPrice(new BigDecimal(price));
        entityManager.persist(item);
        return item;
    }

    private OrderEntity order(Long owner, String status, boolean deleted) {
        OrderEntity order = new OrderEntity();
        order.setUserId(owner);
        order.setStatus(status);
        order.setDeleted(deleted);
        order.setTotalPrice(new BigDecimal("25.00"));
        entityManager.persist(order);
        return order;
    }

    private OrderItemEntity line(OrderEntity order, ItemEntity item, int quantity) {
        OrderItemEntity line = new OrderItemEntity();
        line.setQuantity(quantity);
        order.addOrderItem(line);
        item.addOrderItem(line);
        entityManager.persist(line);
        return line;
    }

    private void setCreatedAt(OrderEntity order, Instant createdAt) {
        entityManager.createNativeQuery("update orders set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", order.getId())
                .executeUpdate();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
