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

class WorkosUsersTests {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer workos = MockRestServiceServer.bindTo(builder).build();
    private final WorkosUsers workosUsers = new WorkosUsers(builder, "sk_test_key");

    @Test
    void deletesTheUserWithTheApiKey() {
        workos.expect(requestTo("https://api.workos.com/user_management/users/user_alice"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer sk_test_key"))
                .andRespond(withSuccess());

        workosUsers.delete("user_alice");

        workos.verify();
    }

    @Test
    void treatsAUserWorkosDoesNotHaveAsDeleted() {
        workos.expect(requestTo("https://api.workos.com/user_management/users/user_alice"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        workosUsers.delete("user_alice");

        workos.verify();
    }

    @Test
    void throwsWhenWorkosRefuses() {
        workos.expect(requestTo("https://api.workos.com/user_management/users/user_alice"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThrows(WorkosUsers.WorkosRequestException.class, () -> workosUsers.delete("user_alice"));
    }

    @Test
    void cannotDeleteWithoutAnApiKey() {
        WorkosUsers unconfigured = new WorkosUsers(RestClient.builder(), "");

        assertFalse(unconfigured.canDelete());
        assertThrows(IllegalStateException.class, () -> unconfigured.delete("user_alice"));
    }
}
