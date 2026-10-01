package dev.matthewsawyer.finance_dashboard.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.model.RecurringFrequency;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringCandidate;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringCandidates;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Recurring payments Jev has guessed, and the user's yes or no to each. */
@RestController
@RequestMapping("/plaid/transactions/recurring/candidates")
public class RecurringCandidatesController {

    static final int MAX_MERCHANT_KEY_LENGTH = 512;

    private final RecurringCandidates candidates;
    private final UserService userService;

    public RecurringCandidatesController(RecurringCandidates candidates, UserService userService) {
        this.candidates = candidates;
        this.userService = userService;
    }

    /** Every candidate in the tracked accounts, or in {@code account_id}, answered or not. */
    @GetMapping
    public Map<String, List<CandidateResponse>> getCandidates(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "account_id", required = false) String accountId
    ) {
        User user = userService.getOrCreateUser(jwt);
        String account = accountId == null || accountId.isBlank() ? null : accountId;
        return Map.of("candidates", candidates.find(user.getId(), account).stream()
                .map(CandidateResponse::from)
                .toList());
    }

    @PutMapping("/answer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void answer(@AuthenticationPrincipal Jwt jwt, @RequestBody AnswerRequest request) {
        User user = userService.getOrCreateUser(jwt);
        if (request == null || request.kind() == null || request.confirmed() == null) {
            throw badRequest("kind and confirmed are required");
        }
        candidates.answer(user.getId(), request.kind(), checkMerchantKey(request.merchantKey()),
                request.confirmed(), request.frequency());
    }

    /** Forgets an answer, so the merchant is suggested again. */
    @DeleteMapping("/answer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void undo(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "kind") RecurringKind kind,
            @RequestParam(name = "merchant_key") String merchantKey
    ) {
        User user = userService.getOrCreateUser(jwt);
        candidates.undo(user.getId(), kind, checkMerchantKey(merchantKey));
    }

    public record AnswerRequest(
            @JsonProperty("kind") RecurringKind kind,
            @JsonProperty("merchant_key") String merchantKey,
            @JsonProperty("confirmed") Boolean confirmed,
            /** How often a payee that repeats is paid, when the user says; null leaves it automatic. */
            @JsonProperty("frequency") RecurringFrequency frequency
    ) {
    }

    /**
     * Shaped like a recurring stream so a confirmed candidate can be listed and planned with
     * Plaid's; {@code stream_id} is made up from the kind and merchant, since a candidate has no
     * stream of its own. It's hashed so it fits where plans store stream ids.
     */
    public record CandidateResponse(
            @JsonProperty("stream_id") String streamId,
            @JsonProperty("kind") RecurringKind kind,
            @JsonProperty("merchant_key") String merchantKey,
            @JsonProperty("account_id") String accountId,
            @JsonProperty("merchant_name") String merchantName,
            @JsonProperty("description") String description,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("iso_currency_code") String isoCurrencyCode,
            @JsonProperty("frequency") RecurringFrequency frequency,
            @JsonProperty("frequency_set") boolean frequencySet,
            @JsonProperty("next_date") LocalDate nextDate,
            @JsonProperty("last_date") LocalDate lastDate,
            @JsonProperty("is_inflow") boolean isInflow,
            @JsonProperty("category") String category,
            @JsonProperty("category_detailed") String categoryDetailed,
            @JsonProperty("share_percent") int sharePercent,
            @JsonProperty("probability") BigDecimal probability,
            @JsonProperty("plan_bucket") SpendingPlanBucket planBucket,
            @JsonProperty("plan_line") String planLine,
            @JsonProperty("status") RecurringCandidate.Status status
    ) {
        static CandidateResponse from(RecurringCandidate candidate) {
            return new CandidateResponse(
                    RecurringCandidatesController.streamId(candidate.kind(), candidate.merchantKey()),
                    candidate.kind(),
                    candidate.merchantKey(),
                    candidate.accountId(),
                    candidate.name(),
                    candidate.name(),
                    candidate.amount(),
                    candidate.isoCurrencyCode(),
                    candidate.frequency(),
                    candidate.frequencySet(),
                    candidate.nextDate(),
                    candidate.lastDate(),
                    candidate.kind() == RecurringKind.PAYCHECK,
                    candidate.category(),
                    candidate.categoryDetailed(),
                    candidate.sharePercent(),
                    candidate.probability(),
                    candidate.planBucket(),
                    candidate.planLine(),
                    candidate.status());
        }
    }

    static String streamId(RecurringKind kind, String merchantKey) {
        return "candidate-" + UUID.nameUUIDFromBytes((kind + ":" + merchantKey).getBytes(StandardCharsets.UTF_8));
    }

    private static String checkMerchantKey(String merchantKey) {
        if (merchantKey == null || merchantKey.isBlank()) {
            throw badRequest("merchant_key is required");
        }
        if (merchantKey.length() > MAX_MERCHANT_KEY_LENGTH) {
            throw badRequest("merchant_key is too long");
        }
        return merchantKey;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
