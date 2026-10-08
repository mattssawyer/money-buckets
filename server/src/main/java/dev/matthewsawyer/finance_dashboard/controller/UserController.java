package dev.matthewsawyer.finance_dashboard.controller;

import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import dev.matthewsawyer.finance_dashboard.users.ClerkUsers;
import dev.matthewsawyer.finance_dashboard.users.UserDeletion;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;
    private final UserDeletion userDeletion;

    public UserController(UserService userService, UserDeletion userDeletion) {
        this.userService = userService;
        this.userDeletion = userDeletion;
    }

    @GetMapping("/me")
    public UserResponse getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.getOrCreateUser(jwt);
        return new UserResponse(user.getId(), user.getClerkUserId(), user.getEmail(),
                user.getDisplayName(), user.getCreatedAt(), user.getUpdatedAt());
    }

    /** Deletes the signed-in user, their data and their sign-in. A failed Plaid request is a 502. */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        if (!userDeletion.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Deleting users needs CLERK_SECRET_KEY on the server");
        }
        try {
            userDeletion.delete(userService.getOrCreateUser(jwt));
        } catch (ClerkUsers.ClerkRequestException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    public record UserResponse(
            UUID id,
            String clerkUserId,
            String email,
            String displayName,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
