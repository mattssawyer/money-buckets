package dev.matthewsawyer.finance_dashboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkosClientValidatorTests {

    private final OAuth2TokenValidator<Jwt> validator = new SecurityConfig().workosClientValidator("client_ours");

    @Test
    void acceptsATokenIssuedToThisApp() {
        assertFalse(validator.validate(token("client_ours")).hasErrors());
    }

    /** Another WorkOS app's tokens are signed by WorkOS too, so only client_id tells them apart. */
    @Test
    void rejectsATokenIssuedToAnotherApp() {
        assertTrue(validator.validate(token("client_theirs")).hasErrors());
    }

    @Test
    void rejectsATokenWithoutAClient() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("user_1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        assertTrue(validator.validate(jwt).hasErrors());
    }

    private static Jwt token(String clientId) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user_1")
                .claim("client_id", clientId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
