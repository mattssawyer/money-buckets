package dev.matthewsawyer.finance_dashboard.service;

import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public UserService(UserRepository userRepository, TransactionTemplate transactionTemplate, Clock clock) {
        this.userRepository = userRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * A new user's first page sends several requests at once, and each can find no user and try
     * to add one. The ones that lose read the winner's instead, outside the transaction the
     * failed insert spoiled.
     */
    public User getOrCreateUser(Jwt jwt) {
        String authUserId = jwt.getSubject();
        try {
            return transactionTemplate.execute(status -> userRepository.findByAuthUserId(authUserId)
                    // Flushed here so a clashing insert fails inside the transaction rather than at commit.
                    .orElseGet(() -> userRepository.saveAndFlush(new User(authUserId))));
        } catch (DataIntegrityViolationException e) {
            return userRepository.findByAuthUserId(authUserId).orElseThrow(() -> e);
        }
    }

    /** Records that the user finished onboarding, so they go straight to Home from now on. */
    public User finishOnboarding(Jwt jwt) {
        User user = getOrCreateUser(jwt);
        user.finishOnboarding(clock.instant());
        return userRepository.save(user);
    }
}
