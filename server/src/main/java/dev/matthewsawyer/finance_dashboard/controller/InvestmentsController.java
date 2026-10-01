package dev.matthewsawyer.finance_dashboard.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory.History;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/investments")
public class InvestmentsController {

    private final BalanceHistory balanceHistory;
    private final UserService userService;
    private final Clock clock;

    public InvestmentsController(BalanceHistory balanceHistory, UserService userService, Clock clock) {
        this.balanceHistory = balanceHistory;
        this.userService = userService;
        this.clock = clock;
    }

    /**
     * Daily net worth from {@code from} (or the first counted snapshot) through today, inclusive.
     * Each current account's series starts at its first snapshot or {@code from}, whichever is
     * later, and omits days when it was dropped.
     *
     * @throws ResponseStatusException with HTTP 400 when {@code from} is after today
     */
    @GetMapping("/history")
    public HistoryResponse getHistory(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from
    ) {
        LocalDate today = LocalDate.now(clock);
        if (from != null && from.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The range must start by today");
        }

        User user = userService.getOrCreateUser(jwt);
        return HistoryResponse.from(balanceHistory.forUser(user.getId(), from, today));
    }

    public record HistoryResponse(
            @JsonProperty("net_worth") List<PointResponse> netWorth,
            @JsonProperty("accounts") List<AccountSeriesResponse> accounts,
            @JsonProperty("accounts_added") List<AccountChangeResponse> accountsAdded,
            @JsonProperty("accounts_dropped") List<AccountChangeResponse> accountsDropped,
            @JsonProperty("left_out_of_net_worth") List<String> leftOutOfNetWorth
    ) {
        static HistoryResponse from(History history) {
            return new HistoryResponse(
                    points(history.netWorth()),
                    history.accounts().stream()
                            .map(series -> new AccountSeriesResponse(series.accountId(), points(series.points())))
                            .toList(),
                    history.accountsAdded().stream()
                            .map(added -> new AccountChangeResponse(added.day(), added.accountId(), added.name()))
                            .toList(),
                    history.accountsDropped().stream()
                            .map(dropped -> new AccountChangeResponse(dropped.day(), dropped.accountId(), dropped.name()))
                            .toList(),
                    history.leftOutOfNetWorth());
        }

        private static List<PointResponse> points(List<BalanceHistory.Point> points) {
            return points.stream().map(point -> new PointResponse(point.day(), point.value())).toList();
        }
    }

    public record PointResponse(
            @JsonProperty("date") LocalDate date,
            @JsonProperty("value") BigDecimal value
    ) {
    }

    public record AccountSeriesResponse(
            @JsonProperty("account_id") String accountId,
            @JsonProperty("points") List<PointResponse> points
    ) {
    }

    public record AccountChangeResponse(
            @JsonProperty("date") LocalDate date,
            @JsonProperty("account_id") String accountId,
            @JsonProperty("name") String name
    ) {
    }
}
