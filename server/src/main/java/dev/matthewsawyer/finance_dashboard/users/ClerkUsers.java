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

/** Deletes sign-ins from Clerk through its Backend API, which needs the instance's secret key. */
@Component
public class ClerkUsers {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final RestClient restClient;
    private final String secretKey;

    @Autowired
    public ClerkUsers(@Value("${clerk.secret-key:}") String secretKey) {
        this(RestClient.builder().requestFactory(requestFactory()), secretKey);
    }

    ClerkUsers(RestClient.Builder restClient, String secretKey) {
        this.restClient = restClient.baseUrl("https://api.clerk.com/v1").build();
        this.secretKey = secretKey;
    }

    /** A hung Clerk would otherwise hold the deleting request open indefinitely. */
    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /** Whether a secret key is set, without which nothing can be deleted. */
    public boolean canDelete() {
        return !secretKey.isBlank();
    }

    /**
     * Deletes a Clerk user, which signs them out everywhere. A user Clerk no longer has counts as
     * deleted.
     *
     * @throws IllegalStateException when no secret key is set
     * @throws ClerkRequestException when Clerk refuses or can't be reached
     */
    public void delete(String clerkUserId) {
        if (!canDelete()) {
            throw new IllegalStateException("CLERK_SECRET_KEY is not set");
        }
        try {
            restClient.delete()
                    .uri("/users/{id}", clerkUserId)
                    .header("Authorization", "Bearer " + secretKey)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (request, response) -> {
                    })
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ClerkRequestException("Clerk user delete failed", e);
        }
    }

    public static class ClerkRequestException extends RuntimeException {

        ClerkRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
