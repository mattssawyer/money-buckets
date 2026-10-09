package dev.matthewsawyer.finance_dashboard.users;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

/** Deletes sign-ins from WorkOS through its User Management API, which needs the API key. */
@Component
public class WorkosUsers {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final RestClient restClient;
    private final String apiKey;

    @Autowired
    public WorkosUsers(@Value("${workos.api-key:}") String apiKey) {
        this(RestClient.builder().requestFactory(requestFactory()), apiKey);
    }

    WorkosUsers(RestClient.Builder restClient, String apiKey) {
        this.restClient = restClient.baseUrl("https://api.workos.com/user_management").build();
        this.apiKey = apiKey;
    }

    /** A hung WorkOS would otherwise hold the deleting request open indefinitely. */
    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /** Whether an API key is set, without which nothing can be deleted. */
    public boolean canDelete() {
        return !apiKey.isBlank();
    }

    /**
     * Deletes a WorkOS user, which signs them out everywhere. A user WorkOS no longer has counts as
     * deleted.
     *
     * @throws IllegalStateException when no API key is set
     * @throws WorkosRequestException when WorkOS refuses or can't be reached
     */
    public void delete(String authUserId) {
        if (!canDelete()) {
            throw new IllegalStateException("WORKOS_API_KEY is not set");
        }
        try {
            restClient.delete()
                    .uri("/users/{id}", authUserId)
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (request, response) -> {
                    })
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new WorkosRequestException("WorkOS user delete failed", e);
        }
    }

    public static class WorkosRequestException extends RuntimeException {

        WorkosRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
