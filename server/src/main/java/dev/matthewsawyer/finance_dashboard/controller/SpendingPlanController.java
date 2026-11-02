package dev.matthewsawyer.finance_dashboard.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlan;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanItem;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.payees.PayeeLookup;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.plan.PlanEvaluation;
import dev.matthewsawyer.finance_dashboard.plan.PlanSuggestions;
import dev.matthewsawyer.finance_dashboard.plan.SpendingHistory;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringPayment;
import dev.matthewsawyer.finance_dashboard.recurring.RecurringPayments;
import dev.matthewsawyer.finance_dashboard.sorting.PlanLines;
import dev.matthewsawyer.finance_dashboard.spending.Spending;
import dev.matthewsawyer.finance_dashboard.sorting.BucketSorting;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.service.SpendingPlanService;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/spending-plan")
public class SpendingPlanController {

    static final int MAX_LINES = 100;
    static final int MAX_ITEMS_PER_LINE = 100;
    static final int MAX_NAME_LENGTH = 255;
    /** NUMERIC(19, 4) holds 15 digits before the decimal point. */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1e15");
    static final BigDecimal MAX_PERCENT = new BigDecimal("100");
    /** The most whole months spending history looks back over. */
    static final int MAX_HISTORY_MONTHS = 12;
    /** How many whole months a line's default amount is averaged over. */
    static final int DEFAULT_AMOUNT_MONTHS = 3;
    /** The longest month is 31 days, first to last. */
    static final int MAX_MONTH_DAYS = 30;

    private final SpendingPlanService planService;
    private final PlaidAccountRepository accountRepository;
    private final BucketSorting bucketSorting;
    private final UserService userService;
    private final Spending spending;
    private final PayeeLookup payeeLookup;
    private final RecurringPayments recurringPayments;

    public SpendingPlanController(
            SpendingPlanService planService,
            PlaidAccountRepository accountRepository,
            BucketSorting bucketSorting,
            UserService userService,
            Spending spending,
            PayeeLookup payeeLookup,
            RecurringPayments recurringPayments
    ) {
        this.planService = planService;
        this.accountRepository = accountRepository;
        this.bucketSorting = bucketSorting;
        this.userService = userService;
        this.spending = spending;
        this.payeeLookup = payeeLookup;
        this.recurringPayments = recurringPayments;
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

    /**
     * How a plan the user is still editing splits its income, without saving it. It's checked
     * like a save, so a percent of gross pay is worked out here too; a blank buffer means none.
     */
    @PostMapping("/evaluate")
    public PlanEvaluationResponse evaluateSpendingPlan(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody SpendingPlanRequest request
    ) {
        userService.getOrCreateUser(jwt);
        if (request == null) {
            throw badRequest("Spending plan is required");
        }
        BigDecimal takeHome = checkAmount(request.takeHome(), "take_home");
        BigDecimal grossPay = checkAmount(request.grossPay(), "gross_pay");
        BigDecimal bufferPercent = checkPercent(request.fixedCostBufferPercent(), "fixed_cost_buffer_percent");
        return PlanEvaluationResponse.from(PlanEvaluation.of(takeHome, bufferPercent, toLines(request.lines(), grossPay)));
    }

    /**
     * What's left of the plan's guilt-free spending between {@code start} and {@code end}, a
     * calendar month in the user's time zone. The plan covers every tracked account, so the
     * spending does too, at the user's share, whichever account the app is showing.
     */
    @GetMapping("/guilt-free-left")
    public GuiltFreeLeftResponse getGuiltFreeLeft(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("start") LocalDate start,
            @RequestParam("end") LocalDate end
    ) {
        User user = userService.getOrCreateUser(jwt);
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > MAX_MONTH_DAYS) {
            throw badRequest("end must be on or after start, at most a month later");
        }
        PlanEvaluation evaluation = planService.find(user.getId(), SpendingPlanController::evaluate)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No spending plan saved"));
        // Null until the plan has take-home pay.
        BigDecimal planned = evaluation.guiltFree().amount();
        Map<String, BigDecimal> totals = Spending.bucketTotals(
                spending.between(user.getId(), start, end, null), payeeLookup.forUser(user.getId())::category);
        BigDecimal spent = totals.getOrDefault(Bucket.GUILT_FREE.name(), BigDecimal.ZERO);
        return new GuiltFreeLeftResponse(
                stripZeros(planned),
                stripZeros(spent),
                planned == null ? null : stripZeros(planned.subtract(spent)),
                stripZeros(totals.getOrDefault(Spending.UNSORTED, BigDecimal.ZERO)));
    }

    /**
     * What a plan starts from, worked out from the payments that count as recurring in every
     * tracked account: take-home pay, the spreadsheet's lines with recurring bills on the lines
     * Jev chose, and the bills on no line to place or skip. Bills already on the saved plan
     * aren't offered to place again, unless {@code new_plan} says the user is starting over.
     */
    @GetMapping("/suggestions")
    public PlanSuggestionsResponse getPlanSuggestions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "new_plan", defaultValue = "false") boolean newPlan
    ) {
        User user = userService.getOrCreateUser(jwt);
        RecurringPayments.Counted counted = recurringPayments.counted(user.getId());
        Set<String> onSavedPlan = newPlan ? Set.of() : planService.find(user.getId(), plan -> plan.getLines().stream()
                        .flatMap(line -> line.getItems().stream())
                        .map(SpendingPlanItem::getStreamId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
        PlanSuggestions suggestions = PlanSuggestions.of(counted.payments(), onSavedPlan);
        return new PlanSuggestionsResponse(
                stripZeros(suggestions.takeHome()),
                suggestions.lines().stream().map(LineResponse::from).toList(),
                counted.payments().stream().map(RecurringPaymentResponse::from).toList(),
                suggestions.unplaced().stream().map(RecurringPaymentResponse::from).toList(),
                counted.suggestedCandidates());
    }

    /**
     * What the user spent in each of the {@code months} whole calendar months before {@code
     * month}, the one they're in now, across every tracked account at their share.
     */
    @GetMapping("/spending-history")
    public SpendingHistoryResponse getSpendingHistory(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("month") @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(name = "months", defaultValue = "3") int months
    ) {
        User user = userService.getOrCreateUser(jwt);
        if (months < 1 || months > MAX_HISTORY_MONTHS) {
            throw badRequest("months must be between 1 and " + MAX_HISTORY_MONTHS);
        }
        LocalDate start = month.minusMonths(months).atDay(1);
        return SpendingHistoryResponse.from(start, lastMonth(month).atEndOfMonth(), history(user, start, month));
    }

    /**
     * What a blank line counts as in plan setup: the user's average monthly spending on it over
     * the three whole months before {@code month}, in whole dollars, by line name. Lines with no
     * spending are left out.
     */
    @GetMapping("/line-defaults")
    public LineDefaultsResponse getLineDefaults(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("month") @DateTimeFormat(pattern = "yyyy-MM") YearMonth month
    ) {
        User user = userService.getOrCreateUser(jwt);
        LocalDate start = month.minusMonths(DEFAULT_AMOUNT_MONTHS).atDay(1);
        return new LineDefaultsResponse(history(user, start, month).lineDefaults());
    }

    /** Spending from {@code start} to the end of the last whole month before {@code month}. */
    private SpendingHistory history(User user, LocalDate start, YearMonth month) {
        PayeeLookup.Payees payees = payeeLookup.forUser(user.getId());
        List<SpendingHistory.Entry> spent = spending.between(
                        user.getId(), start, lastMonth(month).atEndOfMonth(), null)
                .stream()
                .map(each -> SpendingHistory.Entry.of(each, payees))
                .toList();
        return SpendingHistory.of(spent, lastMonth(month));
    }

    /** The last whole month: the one before the month the user is in. */
    private static YearMonth lastMonth(YearMonth month) {
        return month.minusMonths(1);
    }

    private static PlanEvaluation evaluate(SpendingPlan plan) {
        return PlanEvaluation.of(plan.getTakeHome(), plan.getFixedCostBufferPercent(), plan.getLines());
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
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("evaluation") PlanEvaluationResponse evaluation
    ) {
        static SpendingPlanResponse from(SpendingPlan plan) {
            return new SpendingPlanResponse(
                    plan.getAccountId(),
                    stripZeros(plan.getTakeHome()),
                    stripZeros(plan.getGrossPay()),
                    stripZeros(plan.getFixedCostBufferPercent()),
                    plan.getLines().stream().map(LineResponse::from).toList(),
                    plan.getUpdatedAt(),
                    PlanEvaluationResponse.from(evaluate(plan))
            );
        }
    }

    /** See {@link PlanEvaluation}. Statuses are lower case: below, within or above. */
    public record PlanEvaluationResponse(
            @JsonProperty("plan_income") BigDecimal planIncome,
            @JsonProperty("from_paycheck") BigDecimal fromPaycheck,
            @JsonProperty("fixed_cost_subtotal") BigDecimal fixedCostSubtotal,
            @JsonProperty("buffer") BigDecimal buffer,
            @JsonProperty("buckets") Map<SpendingPlanBucket, ShareResponse> buckets,
            @JsonProperty("guilt_free") ShareResponse guiltFree
    ) {
        static PlanEvaluationResponse from(PlanEvaluation evaluation) {
            Map<SpendingPlanBucket, ShareResponse> buckets = new EnumMap<>(SpendingPlanBucket.class);
            evaluation.buckets().forEach((bucket, share) -> buckets.put(bucket, ShareResponse.from(share)));
            return new PlanEvaluationResponse(
                    stripZeros(evaluation.planIncome()),
                    stripZeros(evaluation.fromPaycheck()),
                    stripZeros(evaluation.fixedCostSubtotal()),
                    stripZeros(evaluation.buffer()),
                    buckets,
                    ShareResponse.from(evaluation.guiltFree()));
        }
    }

    /** A part of the plan: its amount, its share of plan income and where that falls against its target. */
    public record ShareResponse(
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("share") BigInteger share,
            @JsonProperty("target") PlanEvaluation.Target target,
            @JsonProperty("status") String status,
            @JsonProperty("flagged") boolean flagged
    ) {
        static ShareResponse from(PlanEvaluation.Share share) {
            String status = share.status() == null ? null : share.status().name().toLowerCase(Locale.ROOT);
            return new ShareResponse(stripZeros(share.amount()), share.share(), share.target(), status, share.flagged());
        }
    }

    /**
     * {@code planned} is what the plan leaves for guilt-free spending. It and {@code left} are
     * null until the plan has take-home pay; {@code left} goes
     * below zero once spending passes the budget. {@code unsorted} is the month's spending not
     * sorted into a bucket yet, which may still turn out to be guilt-free.
     */
    public record GuiltFreeLeftResponse(
            @JsonProperty("planned") BigDecimal planned,
            @JsonProperty("spent") BigDecimal spent,
            @JsonProperty("left") BigDecimal left,
            @JsonProperty("unsorted") BigDecimal unsorted
    ) {
    }

    /**
     * {@code recurring} is every payment that counts as recurring, with the item it makes on a
     * plan; {@code unplaced} the bills among them to place or skip. {@code suggested_candidates}
     * counts the recurring candidates waiting for the user's answer, which aren't filled in.
     */
    public record PlanSuggestionsResponse(
            @JsonProperty("take_home") BigDecimal takeHome,
            @JsonProperty("lines") List<LineResponse> lines,
            @JsonProperty("recurring") List<RecurringPaymentResponse> recurring,
            @JsonProperty("unplaced") List<RecurringPaymentResponse> unplaced,
            @JsonProperty("suggested_candidates") int suggestedCandidates
    ) {
    }

    /** Shaped like a recurring stream from {@code /plaid/transactions/recurring}, with its plan item. */
    public record RecurringPaymentResponse(
            @JsonProperty("stream_id") String streamId,
            @JsonProperty("account_id") String accountId,
            @JsonProperty("merchant_name") String merchantName,
            @JsonProperty("description") String description,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("iso_currency_code") String isoCurrencyCode,
            @JsonProperty("frequency") String frequency,
            @JsonProperty("frequency_set") boolean frequencySet,
            @JsonProperty("next_date") LocalDate nextDate,
            @JsonProperty("last_date") LocalDate lastDate,
            @JsonProperty("is_inflow") boolean isInflow,
            @JsonProperty("category") String category,
            @JsonProperty("category_detailed") String categoryDetailed,
            @JsonProperty("share_percent") int sharePercent,
            @JsonProperty("plan_bucket") SpendingPlanBucket planBucket,
            @JsonProperty("plan_line") String planLine,
            @JsonProperty("kind") RecurringKind kind,
            @JsonProperty("merchant_key") String merchantKey,
            /** DETECTED for Plaid's streams, CONFIRMED for a candidate the user said repeats. */
            @JsonProperty("status") RecurringPayment.Source status,
            /** The payment as a breakdown item: named for who's paid, at the user's monthly share. */
            @JsonProperty("plan_item") ItemResponse planItem
    ) {
        static RecurringPaymentResponse from(RecurringPayment payment) {
            PlanSuggestions.Item item = PlanSuggestions.item(payment);
            return new RecurringPaymentResponse(
                    payment.streamId(),
                    payment.accountId(),
                    payment.merchantName(),
                    payment.description(),
                    payment.amount(),
                    payment.isoCurrencyCode(),
                    payment.frequency(),
                    payment.frequencySet(),
                    payment.nextDate(),
                    payment.lastDate(),
                    payment.inflow(),
                    payment.category(),
                    payment.categoryDetailed(),
                    payment.sharePercent(),
                    payment.planBucket(),
                    payment.planLine(),
                    payment.kind(),
                    payment.merchantKey(),
                    payment.source(),
                    ItemResponse.from(item));
        }
    }

    /** See {@link SpendingHistory}. Months are YYYY-MM. */
    public record SpendingHistoryResponse(
            @JsonProperty("start") LocalDate start,
            @JsonProperty("end") LocalDate end,
            @JsonProperty("months") List<String> months,
            @JsonProperty("groups") List<GroupHistoryResponse> groups
    ) {
        static SpendingHistoryResponse from(LocalDate start, LocalDate end, SpendingHistory history) {
            return new SpendingHistoryResponse(
                    start,
                    end,
                    history.months().stream().map(YearMonth::toString).toList(),
                    history.groups().stream().map(GroupHistoryResponse::from).toList());
        }
    }

    /** {@code bucket} is a bucket's name, or UNSORTED. */
    public record GroupHistoryResponse(
            @JsonProperty("bucket") String bucket,
            @JsonProperty("average") BigDecimal average,
            @JsonProperty("categories") List<CategoryHistoryResponse> categories
    ) {
        static GroupHistoryResponse from(SpendingHistory.Group group) {
            return new GroupHistoryResponse(group.bucket(), stripZeros(group.average()),
                    group.categories().stream().map(CategoryHistoryResponse::from).toList());
        }
    }

    /**
     * A plan line ({@code kind} LINE, named by {@code line}), a Plaid category for spending on no
     * line (CATEGORY, with {@code category} and maybe {@code category_detailed}), or all of a
     * bucket's spending (ALL).
     */
    public record CategoryHistoryResponse(
            @JsonProperty("key") String key,
            @JsonProperty("kind") SpendingHistory.Kind kind,
            @JsonProperty("line") String line,
            @JsonProperty("category") String category,
            @JsonProperty("category_detailed") String categoryDetailed,
            @JsonProperty("average") BigDecimal average,
            @JsonProperty("payees") List<PayeeHistoryResponse> payees
    ) {
        static CategoryHistoryResponse from(SpendingHistory.Category category) {
            return new CategoryHistoryResponse(
                    category.key(),
                    category.kind(),
                    category.line(),
                    category.category(),
                    category.categoryDetailed(),
                    stripZeros(category.average()),
                    category.payees().stream()
                            .map(payee -> new PayeeHistoryResponse(payee.key(), payee.name(), stripZeros(payee.average())))
                            .toList());
        }
    }

    public record PayeeHistoryResponse(
            @JsonProperty("key") String key,
            @JsonProperty("name") String name,
            @JsonProperty("average") BigDecimal average
    ) {
    }

    /** Each line's default amount in whole dollars, by line name. */
    public record LineDefaultsResponse(@JsonProperty("lines") Map<String, BigDecimal> lines) {
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

        /** A suggested line, blank or made of recurring bills. */
        static LineResponse from(PlanSuggestions.Line line) {
            return new LineResponse(
                    line.bucket(),
                    line.name(),
                    null,
                    line.fromPaycheck(),
                    null,
                    line.items().stream().map(ItemResponse::from).toList()
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

        static ItemResponse from(PlanSuggestions.Item item) {
            return new ItemResponse(item.name(), stripZeros(item.amount()), item.streamId());
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
