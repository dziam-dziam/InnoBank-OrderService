package innowise.bank.order.client;

import innowise.bank.order.exception.UserNotFoundByEmailException;
import innowise.bank.order.exception.UserNotFoundByIdException;
import innowise.bank.order.exception.UserDirectoryUnavailableException;
import innowise.bank.order.model.dto.response.ResponseUserDto;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

@Component
public class UserDirectoryClient {

    private final RestClient client;
    private final CircuitBreaker circuitBreaker;

    public UserDirectoryClient(@Value("${services.users.url}") String baseUrl, @Value("${services.users.password}") String password) {
        if (password.isBlank()) {
            throw new IllegalArgumentException("Configure a password for User Service");
        }

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(httpClient);
        requests.setReadTimeout(Duration.ofSeconds(2));

        client = RestClient.builder().baseUrl(baseUrl).requestFactory(requests)
                .defaultHeaders(headers -> headers.setBasicAuth("order-service", password)).build();
        circuitBreaker = CircuitBreaker.of("user-directory", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build());
    }

    public ResponseUserDto getByEmail(String email) {
        return find("/internal/users/by-email?email={email}", email)
                .orElseThrow(UserNotFoundByEmailException::new);
    }

    public ResponseUserDto getById(Long userId) {
        return find("/internal/users/{id}/details", userId)
                .orElseThrow(() -> new UserNotFoundByIdException(userId));
    }

    private Optional<ResponseUserDto> find(String uri, Object identifier) {
        try {
            return circuitBreaker.executeSupplier(() -> requestUser(uri, identifier));
        } catch (CallNotPermittedException exception) {
            throw new UserDirectoryUnavailableException(exception);
        }
    }

    private Optional<ResponseUserDto> requestUser(String uri, Object identifier) {
        try {
            ResponseUserDto user = client.get().uri(uri, identifier).retrieve().body(ResponseUserDto.class);
            if (user == null || user.id() == null || user.email() == null || user.active() == null) {
                throw new UserDirectoryUnavailableException();
            }
            return Optional.of(user);
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw new UserDirectoryUnavailableException(exception);
        }
    }
}
