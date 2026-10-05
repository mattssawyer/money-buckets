package dev.matthewsawyer.finance_dashboard.service;

import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTests {

    private static final Jwt JWT = Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("user_new")
            .issuedAt(Instant.now())
            .build();

    @Test
    void returnsTheUserAnotherRequestAddedWhenBothSignInFirstAtOnce() {
        UserRepository clashing = mock(UserRepository.class);
        User storedFirst = new User("user_new");
        // Nothing is stored when this request looks, but another request adds the user before it saves.
        when(clashing.findByClerkUserId("user_new")).thenReturn(Optional.empty(), Optional.of(storedFirst));
        when(clashing.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("users_clerk_user_id_unique"));
        UserService users = new UserService(clashing, new TransactionTemplate(mock(PlatformTransactionManager.class)));

        assertSame(storedFirst, users.getOrCreateUser(JWT));
    }
}
