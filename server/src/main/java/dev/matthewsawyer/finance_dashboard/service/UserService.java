package dev.matthewsawyer.finance_dashboard.service;

import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;

    public UserService(UserRepository userRepository, TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * A new user's first page sends several requests at once, and each can find no user and try
     * to add one. The ones that lose read the winner's instead, outside the transaction the
     * failed insert spoiled.
     */
    public User getOrCreateUser(Jwt jwt) {
        String clerkUserId = jwt.getSubject();
        try {
            return transactionTemplate.execute(status -> userRepository.findByClerkUserId(clerkUserId)
                    // Flushed here so a clashing insert fails inside the transaction rather than at commit.
                    .orElseGet(() -> userRepository.saveAndFlush(new User(clerkUserId))));
        } catch (DataIntegrityViolationException e) {
            return userRepository.findByClerkUserId(clerkUserId).orElseThrow(() -> e);
        }
    }
}
