package dev.matthewsawyer.finance_dashboard.sorting;

import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanLineChooserTests {

    private static final PlanLines PLAN = new PlanLines(
            List.of("Rent/mortgage", "Subscriptions"), List.of("Roth IRA"), List.of("Vacations"));

    private final TypeSafeClient typeSafe = mock(TypeSafeClient.class);
    private PlanLineChooser chooser;

    @BeforeEach
    void setUp() {
        chooser = new PlanLineChooser(typeSafe, Runnable::run);
        when(typeSafe.isConfigured()).thenReturn(true);
    }

    @Test
    void placesEachPaymentOnTheLineJevChooses() {
        when(typeSafe.ask(any(), any())).thenAnswer(invocation -> {
            String description = ((PlanLineChooser.State) invocation.getArgument(0)).payment().description();
            return answer(switch (description) {
                case "STERLING GROUP WEB PMTS" -> "fixed costs: Rent/mortgage";
                case "VANGUARD ROTH" -> "investments: Roth IRA";
                default -> "none";
            });
        });

        Map<String, PlanLineChooser.Line> chosen = chooser.choose(List.of(
                payment("rent", "STERLING GROUP WEB PMTS"),
                payment("roth", "VANGUARD ROTH"),
                payment("to-joint", "ONLINE TRANSFER TO CHK")), PLAN);

        assertEquals(Map.of(
                "rent", new PlanLineChooser.Line(SpendingPlanBucket.FIXED_COSTS, "Rent/mortgage"),
                "roth", new PlanLineChooser.Line(SpendingPlanBucket.INVESTMENTS, "Roth IRA")), chosen);
    }

    @Test
    @SuppressWarnings("unchecked")
    void offersEveryLineByBucketAndNone() {
        when(typeSafe.ask(any(), any())).thenReturn(answer("none"));

        chooser.choose(List.of(payment("rent", "STERLING GROUP WEB PMTS")), PLAN);

        ArgumentCaptor<Map<String, Map<String, Object>>> questions = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Object> state = ArgumentCaptor.forClass(Object.class);
        verify(typeSafe).ask(state.capture(), questions.capture());
        Map<String, Object> criteria = (Map<String, Object>) questions.getValue().get("line").get("criteria");
        assertEquals(List.of("fixed costs: Rent/mortgage", "fixed costs: Subscriptions", "investments: Roth IRA",
                "savings: Vacations", "none"), List.copyOf(criteria.keySet()));
        PlanLineChooser.PaymentState payment = assertInstanceOf(PlanLineChooser.State.class, state.getValue()).payment();
        assertEquals(new BigDecimal("777.46"), payment.amountUsd());
        assertEquals("semi monthly", payment.frequency());
    }

    @Test
    void leavesOutPaymentsWhoseQuestionFailed() {
        when(typeSafe.ask(any(), any())).thenThrow(new IllegalStateException("TypeSafe unavailable"));

        assertTrue(chooser.choose(List.of(payment("rent", "STERLING GROUP WEB PMTS")), PLAN).isEmpty());
    }

    @Test
    void asksNothingWithoutAnApiKey() {
        when(typeSafe.isConfigured()).thenReturn(false);

        assertTrue(chooser.choose(List.of(payment("rent", "STERLING GROUP WEB PMTS")), PLAN).isEmpty());
        verify(typeSafe, never()).ask(any(), any());
    }

    private static PlanLineChooser.Payment payment(String id, String description) {
        return new PlanLineChooser.Payment(id, description, null, new BigDecimal("-777.456"), "SEMI_MONTHLY");
    }

    private static TypeSafeClient.Answers answer(String line) {
        return new TypeSafeClient.Answers(Map.of("line", TypeSafeClient.Answer.choice(line)));
    }
}
