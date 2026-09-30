package dev.matthewsawyer.finance_dashboard.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.payees.PayeeCorrections;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

/** The user's corrections to a payee's bucket and category, which cover its charges now and later. */
@RestController
@RequestMapping("/payees/corrections")
public class PayeeCorrectionsController {

    static final int MAX_MERCHANT_KEY_LENGTH = 512;
    // Plaid primary categories, such as RENT_AND_UTILITIES.
    private static final Pattern CATEGORY = Pattern.compile("[A-Z_]{1,64}");

    private final PayeeCorrections corrections;
    private final BucketSorting bucketSorting;
    private final UserService userService;

    public PayeeCorrectionsController(
            PayeeCorrections corrections, BucketSorting bucketSorting, UserService userService) {
        this.corrections = corrections;
        this.bucketSorting = bucketSorting;
        this.userService = userService;
    }

    /**
     * Sets the payee's bucket and category; a null one is left to sorting or Plaid. The payee's
     * recurring judgment is refreshed afterwards, since the bucket decides which charges count.
     */
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void correct(@AuthenticationPrincipal Jwt jwt, @RequestBody CorrectionRequest request) {
        User user = userService.getOrCreateUser(jwt);
        if (request == null || (request.bucket() == null && request.category() == null)) {
            throw badRequest("bucket or category is required");
        }
        if (request.category() != null && !CATEGORY.matcher(request.category()).matches()) {
            throw badRequest("category must be a Plaid primary category");
        }
        corrections.correct(user.getId(), checkMerchantKey(request.merchantKey()), request.bucket(), request.category());
        bucketSorting.sortLater(user.getId());
    }

    /** Forgets the payee's correction; its charges are sorted again. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void undo(@AuthenticationPrincipal Jwt jwt, @RequestParam(name = "merchant_key") String merchantKey) {
        User user = userService.getOrCreateUser(jwt);
        corrections.undo(user.getId(), checkMerchantKey(merchantKey));
        bucketSorting.sortLater(user.getId());
    }

    public record CorrectionRequest(
            @JsonProperty("merchant_key") String merchantKey,
            @JsonProperty("bucket") Bucket bucket,
            @JsonProperty("category") String category
    ) {
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
