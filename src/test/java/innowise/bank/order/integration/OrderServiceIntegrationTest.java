package innowise.bank.order.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.BasicCredentials;
import com.github.tomakehurst.wiremock.client.WireMock;
import innowise.bank.order.model.dto.request.CreateItemDto;
import innowise.bank.order.model.dto.request.CreateOrderDto;
import innowise.bank.order.model.dto.request.OrderLineDto;
import innowise.bank.order.model.dto.request.UpdateItemDto;
import innowise.bank.order.model.dto.request.UpdateOrderDto;
import innowise.bank.order.model.dto.response.ResponseItemDto;
import innowise.bank.order.model.dto.response.ResponseOrderDetailsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("postgres-integration")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(OrderServiceIntegrationTest.Upstreams.class)
class OrderServiceIntegrationTest {

    private static final long USER_ID = 42L;
    private static final String EMAIL = "jan+orders@example.com";
    private static final String ADMIN_TOKEN = "test-admin-token";
    private static final String SERVICE_PASSWORD = "test-service-password";
    private static final BasicCredentials USER_SERVICE_AUTH = new BasicCredentials("order-service", SERVICE_PASSWORD);
    private static final String BY_EMAIL_PATH = "/internal/users/by-email";
    private static final String USER_DETAILS_PATH = "/internal/users/" + USER_ID + "/details";
    private static final String AUTH_PATH = "/api/auth/validate";
    private static final String USER_PROFILE = """
            {"id":42,"name":"Jan","surname":"Kowalski","birthDate":"1990-01-01",
             "email":"jan+orders@example.com","active":true}
            """;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("orders_integration");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate database;
    @Autowired @Qualifier("authServer") private WireMockServer authServer;
    @Autowired @Qualifier("userServer") private WireMockServer userServer;

    @BeforeEach
    void resetDatabaseAndStubs() {
        database.execute("TRUNCATE TABLE order_items, orders, items RESTART IDENTITY");
        authServer.resetAll();
        userServer.resetAll();
        authServer.stubFor(WireMock.get(urlEqualTo(AUTH_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + ADMIN_TOKEN))
                .willReturn(okJson("""
                        {"valid":true,"userId":1,"role":"ADMIN"}
                        """)));
    }

    @Test
    void shouldCreateReadUpdateAndSoftDeleteOrderWithUserProfile() throws Exception {
        stubUserProfile();
        long itemId = createItem();
        ResponseOrderDetailsDto created = createOrder(itemId);
        long orderId = created.id();

        assertThat(created.user().email()).isEqualTo(EMAIL);
        assertThat(created.totalPrice()).isEqualByComparingTo("25.00");
        assertThat(database.queryForObject("SELECT user_id FROM orders WHERE id = ?", Long.class, orderId))
                .isEqualTo(USER_ID);
        userServer.verify(1, getRequestedFor(urlPathEqualTo(BY_EMAIL_PATH))
                .withQueryParam("email", equalTo(EMAIL))
                .withBasicAuth(USER_SERVICE_AUTH));

        mvc.perform(admin(get("/api/orders/{id}", orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].itemId").value(itemId))
                .andExpect(jsonPath("$.user.email").value(EMAIL));
        mvc.perform(admin(get("/api/orders")).param("statuses", "NEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].user.email").value(EMAIL));
        mvc.perform(admin(get("/api/orders/by-user/{userId}", USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(orderId));

        mvc.perform(admin(put("/api/orders/{id}", orderId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new UpdateOrderDto("PAID", List.of(new OrderLineDto(itemId, 3))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalPrice").value(37.50))
                .andExpect(jsonPath("$.user.email").value(EMAIL));
        assertThat(database.queryForList("SELECT quantity FROM order_items WHERE order_id = ?", Integer.class, orderId))
                .containsExactly(3);

        mvc.perform(admin(delete("/api/items/{id}", itemId)))
                .andExpect(status().isConflict());
        userServer.resetRequests();
        mvc.perform(admin(delete("/api/orders/{id}", orderId))).andExpect(status().isNoContent());
        mvc.perform(admin(get("/api/orders/{id}", orderId))).andExpect(status().isNotFound());
        assertThat(database.queryForObject("SELECT deleted FROM orders WHERE id = ?", Boolean.class, orderId))
                .isTrue();
        assertThat(userServer.getAllServeEvents()).isEmpty();
    }

    @Test
    void shouldRollBackOrderStatusAndPositionsWhenReplacementItemIsMissing() throws Exception {
        stubUserProfile();
        long itemId = createItem();
        ResponseOrderDetailsDto created = createOrder(itemId);

        mvc.perform(admin(put("/api/orders/{id}", created.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new UpdateOrderDto("PAID", List.of(new OrderLineDto(Long.MAX_VALUE, 3))))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mvc.perform(admin(get("/api/orders/{id}", created.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.totalPrice").value(25))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(created.items().getFirst().id()))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void shouldReturnNotFoundForUnknownUserAndKeepOrdersTableEmpty() throws Exception {
        userServer.stubFor(WireMock.get(urlPathEqualTo(BY_EMAIL_PATH))
                .withQueryParam("email", equalTo(EMAIL))
                .withBasicAuth("order-service", SERVICE_PASSWORD)
                .willReturn(notFound()));

        mvc.perform(createOrderRequest(createItem()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(database.queryForObject("SELECT count(*) FROM orders", Long.class)).isZero();
        assertThat(database.queryForObject("SELECT count(*) FROM order_items", Long.class)).isZero();
    }

    @Test
    void shouldReturnServiceUnavailableAndLeaveOrderUnchangedWhenUserServiceFails() throws Exception {
        stubUserProfile();
        ResponseOrderDetailsDto created = createOrder(createItem());
        userServer.resetAll();
        userServer.stubFor(WireMock.get(urlEqualTo(USER_DETAILS_PATH))
                .withBasicAuth("order-service", SERVICE_PASSWORD)
                .willReturn(serverError()));

        mvc.perform(admin(put("/api/orders/{id}", created.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateOrderDto("PAID", null))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(database.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, created.id()))
                .isEqualTo("NEW");
        userServer.verify(1, getRequestedFor(urlEqualTo(USER_DETAILS_PATH))
                .withBasicAuth(USER_SERVICE_AUTH));
    }

    @Test
    void shouldUpdateAndDeleteUnreferencedCatalogItem() throws Exception {
        long itemId = createItem();

        mvc.perform(admin(put("/api/items/{id}", itemId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateItemDto("Planner", new BigDecimal("19.99")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Planner"));
        mvc.perform(admin(get("/api/items/{id}", itemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(19.99));
        mvc.perform(admin(delete("/api/items/{id}", itemId))).andExpect(status().isNoContent());
        mvc.perform(admin(get("/api/items/{id}", itemId))).andExpect(status().isNotFound());
        assertThat(database.queryForObject("SELECT count(*) FROM items WHERE id = ?", Long.class, itemId)).isZero();
    }

    @Test
    void shouldRejectTokenRejectedByAuthService() throws Exception {
        authServer.stubFor(WireMock.get(urlEqualTo(AUTH_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer invalid-test-token"))
                .willReturn(aResponse().withStatus(401)));

        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer invalid-test-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(userServer.getAllServeEvents()).isEmpty();
    }

    private void stubUserProfile() {
        userServer.stubFor(WireMock.get(urlPathEqualTo(BY_EMAIL_PATH))
                .withQueryParam("email", equalTo(EMAIL))
                .withBasicAuth("order-service", SERVICE_PASSWORD)
                .willReturn(okJson(USER_PROFILE)));
        userServer.stubFor(WireMock.get(urlEqualTo(USER_DETAILS_PATH))
                .withBasicAuth("order-service", SERVICE_PASSWORD)
                .willReturn(okJson(USER_PROFILE)));
    }

    private long createItem() throws Exception {
        String response = mvc.perform(admin(post("/api/items"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateItemDto("Notebook", new BigDecimal("12.50")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readValue(response, ResponseItemDto.class).id();
    }

    private ResponseOrderDetailsDto createOrder(long itemId) throws Exception {
        MockHttpServletResponse response = mvc.perform(createOrderRequest(itemId))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        ResponseOrderDetailsDto order = mapper.readValue(response.getContentAsString(), ResponseOrderDetailsDto.class);
        assertThat(response.getHeader(HttpHeaders.LOCATION)).endsWith("/api/orders/" + order.id());
        return order;
    }

    private MockHttpServletRequestBuilder createOrderRequest(long itemId) {
        CreateOrderDto request = new CreateOrderDto(null, EMAIL, "NEW", List.of(new OrderLineDto(itemId, 2)));
        return admin(post("/api/orders")).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request));
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Upstreams {

        @Bean(initMethod = "start", destroyMethod = "stop")
        WireMockServer authServer() {
            return new WireMockServer(wireMockConfig().bindAddress("127.0.0.1").dynamicPort());
        }

        @Bean(initMethod = "start", destroyMethod = "stop")
        WireMockServer userServer() {
            return new WireMockServer(wireMockConfig().bindAddress("127.0.0.1").dynamicPort());
        }

        @Bean
        DynamicPropertyRegistrar serviceUrls(@Qualifier("authServer") WireMockServer auth,
                                             @Qualifier("userServer") WireMockServer users) {
            return registry -> {
                registry.add("services.auth.url", auth::baseUrl);
                registry.add("services.users.url", users::baseUrl);
                registry.add("services.users.password", () -> SERVICE_PASSWORD);
            };
        }
    }
}
