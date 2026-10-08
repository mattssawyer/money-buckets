package dev.matthewsawyer.finance_dashboard.users;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ClerkUsersTests {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer clerk = MockRestServiceServer.bindTo(builder).build();
    private final ClerkUsers clerkUsers = new ClerkUsers(builder, "sk_test_secret");

    @Test
    void deletesTheUserWithTheSecretKey() {
        clerk.expect(requestTo("https://api.clerk.com/v1/users/user_alice"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer sk_test_secret"))
                .andRespond(withSuccess());

        clerkUsers.delete("user_alice");

        clerk.verify();
    }

    @Test
    void treatsAUserClerkDoesNotHaveAsDeleted() {
        clerk.expect(requestTo("https://api.clerk.com/v1/users/user_alice"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        clerkUsers.delete("user_alice");

        clerk.verify();
    }

    @Test
    void throwsWhenClerkRefuses() {
        clerk.expect(requestTo("https://api.clerk.com/v1/users/user_alice"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThrows(ClerkUsers.ClerkRequestException.class, () -> clerkUsers.delete("user_alice"));
    }

    @Test
    void cannotDeleteWithoutASecretKey() {
        ClerkUsers unconfigured = new ClerkUsers(RestClient.builder(), "");

        assertFalse(unconfigured.canDelete());
        assertThrows(IllegalStateException.class, () -> unconfigured.delete("user_alice"));
    }
}
