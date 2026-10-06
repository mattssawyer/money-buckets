package dev.matthewsawyer.finance_dashboard.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import dev.matthewsawyer.finance_dashboard.transactions.TransactionExclusions;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/transactions/counting")
public class TransactionCountingController {
    private final TransactionExclusions exclusions;
    private final UserService users;
    private final BucketSorting sorting;

    public TransactionCountingController(TransactionExclusions exclusions, UserService users, BucketSorting sorting) {
        this.exclusions = exclusions;
        this.users = users;
        this.sorting = sorting;
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void set(@AuthenticationPrincipal Jwt jwt, @RequestBody CountingRequest request) {
        if (request == null || request.transactionId() == null || request.transactionId().isBlank()
                || request.transactionId().length() > 255 || request.excluded() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transaction_id and excluded are required");
        }
        var user = users.getOrCreateUser(jwt);
        exclusions.set(user.getId(), request.transactionId(), request.excluded(), request.future());
        sorting.sortLater(user.getId());
    }

    public record CountingRequest(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("excluded") Boolean excluded,
            @JsonProperty("future") boolean future) {}
}
