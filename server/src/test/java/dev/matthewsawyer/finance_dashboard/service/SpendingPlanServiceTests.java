package dev.matthewsawyer.finance_dashboard.service;

import dev.matthewsawyer.finance_dashboard.model.SpendingPlan;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanItem;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@Transactional
class SpendingPlanServiceTests {

    @Autowired private SpendingPlanService planService;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = users.saveAndFlush(new User("spending-plan-test-user")).getId();
    }

    @Test
    void findsNothingBeforeTheFirstSave() {
        assertTrue(planService.find(userId, plan -> plan).isEmpty());
    }

    @Test
    void savesAPlanWithLinesAndItemsInOrder() {
        planService.save(userId, "checking", new BigDecimal("5200"), new BigDecimal("8000"), new BigDecimal("12.5"),
                List.of(
                new SpendingPlanLine(SpendingPlanBucket.FIXED_COSTS, "Rent/mortgage",
                        new BigDecimal("1450"), false, null, List.of()),
                new SpendingPlanLine(SpendingPlanBucket.FIXED_COSTS, "Subscriptions", null, false, null, List.of(
                        new SpendingPlanItem("Netflix", new BigDecimal("15.49"), "stream-netflix"),
                        new SpendingPlanItem("Spotify", new BigDecimal("11.99"), null))),
                new SpendingPlanLine(SpendingPlanBucket.INVESTMENTS, "401(k)",
                        new BigDecimal("600"), true, new BigDecimal("7.5123456789012345"), List.of())
        ), plan -> plan);
        entityManager.clear();

        Summary saved = planService.find(userId, Summary::of).orElseThrow();

        assertEquals("checking", saved.accountId());
        assertEquals(0, new BigDecimal("8000").compareTo(saved.grossPay()));
        assertEquals(0, new BigDecimal("7.5123456789012345").compareTo(saved.percentOfGross().get(2)));
        assertNull(saved.percentOfGross().get(0));
        assertEquals(0, new BigDecimal("5200").compareTo(saved.takeHome()));
        assertEquals(0, new BigDecimal("12.5").compareTo(saved.bufferPercent()));
        assertEquals(List.of("Rent/mortgage", "Subscriptions", "401(k)"), saved.lineNames());
        assertEquals(List.of("Netflix", "Spotify"), saved.itemNames().get(1));
        assertEquals(List.of("stream-netflix", "none"), saved.streamIds().get(1));
        assertTrue(saved.fromPaycheck().get(2));
    }

    @Test
    void replacesEveryLineOnTheNextSave() {
        planService.save(userId, "checking", new BigDecimal("5200"), null, SpendingPlan.DEFAULT_BUFFER_PERCENT, List.of(
                new SpendingPlanLine(SpendingPlanBucket.FIXED_COSTS, "Subscriptions", null, false, null, List.of(
                        new SpendingPlanItem("Netflix", new BigDecimal("15.49"), "stream-netflix"))),
                new SpendingPlanLine(SpendingPlanBucket.SAVINGS, "Vacations",
                        new BigDecimal("200"), false, null, List.of())
        ), plan -> plan);
        UUID planId = planService.find(userId, SpendingPlan::getId).orElseThrow();

        planService.save(userId, "checking", null, null, BigDecimal.ZERO, List.of(
                new SpendingPlanLine(SpendingPlanBucket.SAVINGS, "Emergency fund",
                        new BigDecimal("300"), false, null, List.of())
        ), plan -> plan);
        entityManager.clear();

        Summary saved = planService.find(userId, Summary::of).orElseThrow();
        assertEquals(planId, planService.find(userId, SpendingPlan::getId).orElseThrow());
        assertNull(saved.takeHome());
        assertEquals(0, BigDecimal.ZERO.compareTo(saved.bufferPercent()));
        assertEquals(List.of("Emergency fund"), saved.lineNames());
        assertEquals(1, count("spending_plan_lines"));
        assertEquals(0, count("spending_plan_items"));
        assertEquals(1, count("spending_plans"));
    }

    private int count(String table) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                .orElse(0);
    }

    /** Flattens a plan while its lazy collections are still loadable. */
    private record Summary(
            String accountId,
            BigDecimal takeHome,
            BigDecimal grossPay,
            BigDecimal bufferPercent,
            List<String> lineNames,
            List<Boolean> fromPaycheck,
            List<BigDecimal> percentOfGross,
            List<List<String>> itemNames,
            List<List<String>> streamIds
    ) {
        static Summary of(SpendingPlan plan) {
            return new Summary(
                    plan.getAccountId(),
                    plan.getTakeHome(),
                    plan.getGrossPay(),
                    plan.getFixedCostBufferPercent(),
                    plan.getLines().stream().map(SpendingPlanLine::getName).toList(),
                    plan.getLines().stream().map(SpendingPlanLine::isFromPaycheck).toList(),
                    plan.getLines().stream().map(SpendingPlanLine::getPercentOfGross).toList(),
                    plan.getLines().stream()
                            .map(line -> line.getItems().stream().map(SpendingPlanItem::getName).toList())
                            .toList(),
                    plan.getLines().stream()
                            .map(line -> line.getItems().stream()
                                    .map(item -> Optional.ofNullable(item.getStreamId()).orElse("none"))
                                    .toList())
                            .toList()
            );
        }
    }
}
