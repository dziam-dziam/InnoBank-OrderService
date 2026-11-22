package innowise.bank.order.client;

import com.sun.net.httpserver.HttpServer;
import innowise.bank.order.exception.UserDirectoryUnavailableException;
import innowise.bank.order.exception.UserNotFoundByEmailException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserDirectoryClientTest {

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private UserDirectoryClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/users/", exchange -> {
            try (exchange) {
                requests.incrementAndGet();
                query.set(exchange.getRequestURI().getRawQuery());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                int status = responseStatus.get();
                if (status == 200) {
                    byte[] body = """
                            {"id":42,"name":"Jan","surname":"Kowalski","birthDate":"1990-01-01",
                             "email":"jan+orders@example.com","active":true}
                            """.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                } else {
                    exchange.sendResponseHeaders(status, -1);
                }
            }
        });
        server.start();
        client = new UserDirectoryClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-password");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldEncodeEmailAndAuthenticateAsOrderService() {
        assertThat(client.getByEmail("jan+orders@example.com").id()).isEqualTo(42L);
        assertThat(query.get()).isEqualTo("email=jan%2Borders%40example.com");
        String credentials = Base64.getEncoder().encodeToString("order-service:test-password"
                .getBytes(StandardCharsets.UTF_8));
        assertThat(authorization.get()).isEqualTo("Basic " + credentials);
    }

    @Test
    void shouldKeepCallingUserServiceAfterMissingUsers() {
        responseStatus.set(404);
        for (int attempt = 0; attempt < 6; attempt++) {
            assertThatThrownBy(() -> client.getByEmail("missing@example.com"))
                    .isInstanceOf(UserNotFoundByEmailException.class);
        }
        responseStatus.set(200);

        assertThat(client.getById(42L).id()).isEqualTo(42L);
        assertThat(requests.get()).isEqualTo(7);
    }

    @Test
    void shouldStopBothLookupsAfterTheCircuitOpens() {
        responseStatus.set(500);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> client.getByEmail("jan+orders@example.com"))
                    .isInstanceOf(UserDirectoryUnavailableException.class);
        }
        responseStatus.set(200);

        assertThatThrownBy(() -> client.getById(42L)).isInstanceOf(UserDirectoryUnavailableException.class);
        assertThat(requests.get()).isEqualTo(5);
    }
}
