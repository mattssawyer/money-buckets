package dev.matthewsawyer.finance_dashboard.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlan;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanItem;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.sorting.PlanLines;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.service.SpendingPlanService;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/spending-plan")
public class SpendingPlanController {

    static final int MAX_LINES = 100;
    static final int MAX_ITEMS_PER_LINE = 100;
    static final int MAX_NAME_LENGTH = 255;
    /** NUMERIC(19, 4) holds 15 digits before the decimal point. */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1e15");
    static final BigDecimal MAX_PERCENT = new BigDecimal("100");

    private final SpendingPlanService planService;
    private final PlaidAccountRepository accountRepository;
    private final BucketSorting bucketSorting;
    private final UserService userService;

    public SpendingPlanController(
            SpendingPlanService planService,
            PlaidAccountRepository accountRepository,
            BucketSorting bucketSorting,
            UserService userService
    ) {
        this.planService = planService;
        this.accountRepository = accountRepository;
        this.bucketSorting = bucketSorting;
        this.userService = userService;
    }

    @GetMapping
    public SpendingPlanResponse getSpendingPlan(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.getOrCreateUser(jwt);
        return planService.find(user.getId(), SpendingPlanResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No spending plan saved"));
    }

    @PutMapping
    public SpendingPlanResponse saveSpendingPlan(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody SpendingPlanRequest request
    ) {
        User user = userService.getOrCreateUser(jwt);
        if (request == null) {
            throw badRequest("Spending plan is required");
        }
        String accountId = blankToNull(request.accountId());
        if (accountId != null && !accountRepository.existsByAccountIdAndUserId(accountId, user.getId())) {
            throw badRequest("Unknown account");
        }
        BigDecimal takeHome = checkAmount(request.takeHome(), "take_home");
        BigDecimal grossPay = checkAmount(request.grossPay(), "gross_pay");
        BigDecimal bufferPercent = checkBufferPercent(request.fixedCostBufferPercent());
        List<SpendingPlanLine> lines = toLines(request.lines(), grossPay);

        PlanLines linesBefore = planService.find(user.getId(), PlanLines::of).orElse(PlanLines.NONE);
        SpendingPlanResponse saved = planService.save(
                user.getId(), accountId, takeHome, grossPay, bufferPercent, lines, SpendingPlanResponse::from);
        bucketSorting.planSaved(user.getId(), linesBefore);
        return saved;
    }

    private static List<SpendingPlanLine> toLines(List<LineRequest> lines, BigDecimal grossPay) {
        List<LineRequest> requested = Objects.requireNonNullElse(lines, List.of());
        if (requested.size() > MAX_LINES) {
            throw badRequest("A plan can have at most " + MAX_LINES + " lines");
        }
        return requested.stream().map(line -> toLine(line, grossPay)).toList();
    }

    private static SpendingPlanLine toLine(LineRequest line, BigDecimal grossPay) {
        if (line == null || line.bucket() == null) {
            throw badRequest("Each line needs a bucket");
        }
        if (line.fromPaycheck() && line.bucket() != SpendingPlanBucket.INVESTMENTS) {
            throw badRequest("Only investment lines can be taken from the paycheck");
        }
        List<ItemRequest> items = Objects.requireNonNullElse(line.items(), List.of());
        if (items.size() > MAX_ITEMS_PER_LINE) {
            throw badRequest("A line can have at most " + MAX_ITEMS_PER_LINE + " items");
        }
        BigDecimal percentOfGross = checkPercent(line.percentOfGross(), "percent_of_gross");
        BigDecimal amount = checkAmount(line.amount(), "amount");
        if (percentOfGross != null) {
            if (!line.fromPaycheck() || !items.isEmpty()) {
                throw badRequest("Only paycheck lines without items can be a percent of gross pay");
            }
            if (grossPay == null || grossPay.signum() == 0) {
                throw badRequest("A percent of gross pay needs gross_pay");
            }
            // Store the entered percent unchanged and derive dollars rather than trusting the client.
            amount = grossPay.multiply(percentOfGross).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
        }
        return new SpendingPlanLine(
                line.bucket(),
                checkName(line.name()),
                amount,
                line.fromPaycheck(),
                percentOfGross,
                items.stream().map(SpendingPlanController::toItem).toList()
        );
    }

    private static SpendingPlanItem toItem(ItemRequest item) {
        if (item == null) {
            throw badRequest("Items cannot be empty");
        }
        String streamId = blankToNull(item.streamId());
        if (streamId != null && streamId.length() > MAX_NAME_LENGTH) {
            throw badRequest("stream_id is too long");
        }
        return new SpendingPlanItem(checkName(item.name()), checkAmount(item.amount(), "amount"), streamId);
    }

    /** Names are free text and may be blank while a plan is being filled in. */
    private static String checkName(String name) {
        String value = name == null ? "" : name.strip();
        if (value.length() > MAX_NAME_LENGTH) {
            throw badRequest("Names can be at most " + MAX_NAME_LENGTH + " characters");
        }
        return value;
    }

    /** Null means left blank, which is different from an entered zero. */
    private static BigDecimal checkAmount(BigDecimal amount, String field) {
        if (amount == null) {
            return null;
        }
        if (amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) >= 0) {
            throw badRequest(field + " must be between 0 and " + MAX_AMOUNT.toPlainString());
        }
        return amount;
    }

    /** Left out means the plan's default buffer. */
    private static BigDecimal checkBufferPercent(BigDecimal percent) {
        if (percent == null) {
            return SpendingPlan.DEFAULT_BUFFER_PERCENT;
        }
        return checkPercent(percent, "fixed_cost_buffer_percent");
    }

    private static BigDecimal checkPercent(BigDecimal percent, String field) {
        if (percent == null) {
            return null;
        }
        if (percent.signum() < 0 || percent.compareTo(MAX_PERCENT) > 0) {
            throw badRequest(field + " must be between 0 and 100");
        }
        return percent;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record SpendingPlanRequest(
            @JsonProperty("account_id") String accountId,
            @JsonProperty("take_home") BigDecimal takeHome,
            @JsonProperty("gross_pay") BigDecimal grossPay,
            @JsonProperty("fixed_cost_buffer_percent") BigDecimal fixedCostBufferPercent,
            @JsonProperty("lines") List<LineRequest> lines
    ) {
    }

    public record LineRequest(
            @JsonProperty("bucket") SpendingPlanBucket bucket,
            @JsonProperty("name") String name,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("from_paycheck") boolean fromPaycheck,
            @JsonProperty("percent_of_gross") BigDecimal percentOfGross,
            @JsonProperty("items") List<ItemRequest> items
    ) {
    }

    public record ItemRequest(
            @JsonProperty("name") String name,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("stream_id") String streamId
    ) {
    }

    public record SpendingPlanResponse(
            @JsonProperty("account_id") String accountId,
            @JsonProperty("take_home") BigDecimal takeHome,
            @JsonProperty("gross_pay") BigDecimal grossPay,
            @JsonProperty("fixed_cost_buffer_percent") BigDecimal fixedCostBufferPercent,
            @JsonProperty("lines") List<LineResponse> lines,
            @JsonProperty("updated_at") Instant updatedAt
    ) {
        static SpendingPlanResponse from(SpendingPlan plan) {
            return new SpendingPlanResponse(
                    plan.getAccountId(),
                    stripZeros(plan.getTakeHome()),
                    stripZeros(plan.getGrossPay()),
                    stripZeros(plan.getFixedCostBufferPercent()),
                    plan.getLines().stream().map(LineResponse::from).toList(),
                    plan.getUpdatedAt()
            );
        }
    }

    public record LineResponse(
            @JsonProperty("bucket") SpendingPlanBucket bucket,
            @JsonProperty("name") String name,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("from_paycheck") boolean fromPaycheck,
            @JsonProperty("percent_of_gross") BigDecimal percentOfGross,
            @JsonProperty("items") List<ItemResponse> items
    ) {
        static LineResponse from(SpendingPlanLine line) {
            return new LineResponse(
                    line.getBucket(),
                    line.getName(),
                    stripZeros(line.getAmount()),
                    line.isFromPaycheck(),
                    stripZeros(line.getPercentOfGross()),
                    line.getItems().stream().map(ItemResponse::from).toList()
            );
        }
    }

    public record ItemResponse(
            @JsonProperty("name") String name,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("stream_id") String streamId
    ) {
        static ItemResponse from(SpendingPlanItem item) {
            return new ItemResponse(item.getName(), stripZeros(item.getAmount()), item.getStreamId());
        }
    }

    /** NUMERIC(19, 4) reads back as 1450.0000; send it the way it was entered. */
    private static BigDecimal stripZeros(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        // 1450 strips to 1.45E+3; keep it as a plain whole number.
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
