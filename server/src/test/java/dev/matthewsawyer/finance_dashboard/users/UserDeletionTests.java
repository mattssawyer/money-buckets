package dev.matthewsawyer.finance_dashboard.users;

import com.plaid.client.model.ItemRemoveRequest;
import com.plaid.client.model.ItemRemoveResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.AccountDrop;
import dev.matthewsawyer.finance_dashboard.model.BalanceSnapshot;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.PayeeCorrection;
import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringAnswer;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.RecurringMerchant;
import dev.matthewsawyer.finance_dashboard.model.RecurringPayee;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlan;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanItem;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;
import dev.matthewsawyer.finance_dashboard.model.TransactionCounting;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.plaid.PlaidTokenEncryption;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Alice and Bob each have one of everything the app stores, and Alice deletes herself. Her rows
 * are found through every table with a user_id column, so a new table is checked without
 * changing this test.
 */
@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@AutoConfigureMockMvc
@Transactional
class UserDeletionTests {

    private static final String ALICE = "user_alice";
    private static final String BOB = "user_bob";

    @TestConfiguration
    static class Tokens {
        /** Accepts any token as the user it names, in place of Clerk checking real ones. */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject(token)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60))
                    .build();
        }
    }

    @MockitoBean private PlaidApi plaidApi;
    @MockitoBean private ClerkUsers clerkUsers;

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaidTokenEncryption tokenEncryption;

    private UUID aliceId;
    private UUID bobId;
    private Map<String, Integer> bobsRows;

    @BeforeEach
    void seed() {
        when(clerkUsers.canDelete()).thenReturn(true);
        aliceId = seedEverything(ALICE, "alice");
        bobId = seedEverything(BOB, "bob");
        // Alice removed a bank before, which Plaid no longer has.
        PlaidItem removed = new PlaidItem("alice-old-item", "alice-old-token", aliceId);
        entityManager.persist(removed);
        entityManager.flush();
        jdbcTemplate.update("UPDATE plaid_items SET removed_on = ? WHERE item_id = ?",
                LocalDate.now().minusMonths(1), "alice-old-item");
        entityManager.clear();
        bobsRows = rowsOf(bobId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletesEverythingOfAlicesAndNothingOfBobs() throws Exception {
        Call<ItemRemoveResponse> call = mock(Call.class);
        when(plaidApi.itemRemove(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(new ItemRemoveResponse()));
        assertFalse(rowsOf(aliceId).values().stream().allMatch(count -> count == 0),
                "Alice should have rows to delete, or this test proves nothing");

        mockMvc.perform(delete("/users/me").header("Authorization", "Bearer " + ALICE))
                .andExpect(status().isNoContent());

        ArgumentCaptor<ItemRemoveRequest> removed = ArgumentCaptor.forClass(ItemRemoveRequest.class);
        verify(plaidApi).itemRemove(removed.capture());
        assertEquals("alice-access-token", removed.getValue().getAccessToken());
        verify(clerkUsers).delete(ALICE);

        rowsOf(aliceId).forEach((table, count) -> assertEquals(0, count, "Alice still has rows in " + table));
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE id = ?", aliceId));
        assertEquals(bobsRows, rowsOf(bobId));
        assertEquals(1, count("SELECT COUNT(*) FROM spending_plan_lines"));
        assertEquals(1, count("SELECT COUNT(*) FROM spending_plan_items"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsEverythingWhenPlaidCantRemoveABank() throws Exception {
        Call<ItemRemoveResponse> call = mock(Call.class);
        when(plaidApi.itemRemove(any())).thenReturn(call);
        when(call.execute()).thenThrow(new IOException("Plaid unreachable"));
        Map<String, Integer> alicesRows = rowsOf(aliceId);

        mockMvc.perform(delete("/users/me").header("Authorization", "Bearer " + ALICE))
                .andExpect(status().isBadGateway());

        assertEquals(alicesRows, rowsOf(aliceId));
        verify(clerkUsers, never()).delete(any());
    }

    @Test
    void refusesWithoutClerksSecretKey() throws Exception {
        when(clerkUsers.canDelete()).thenReturn(false);
        Map<String, Integer> alicesRows = rowsOf(aliceId);

        mockMvc.perform(delete("/users/me").header("Authorization", "Bearer " + ALICE))
                .andExpect(status().isServiceUnavailable());

        assertEquals(alicesRows, rowsOf(aliceId));
        verifyNoInteractions(plaidApi);
        verify(clerkUsers, never()).delete(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void canBeRunAgainWhenClerkFails() throws Exception {
        Call<ItemRemoveResponse> call = mock(Call.class);
        when(plaidApi.itemRemove(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(new ItemRemoveResponse()));
        doThrow(new ClerkUsers.ClerkRequestException("Clerk down", null)).when(clerkUsers).delete(ALICE);

        mockMvc.perform(delete("/users/me").header("Authorization", "Bearer " + ALICE))
                .andExpect(status().isBadGateway());
        rowsOf(aliceId).forEach((table, count) -> assertEquals(0, count, "Alice still has rows in " + table));

        doNothing().when(clerkUsers).delete(ALICE);
        mockMvc.perform(delete("/users/me").header("Authorization", "Bearer " + ALICE))
                .andExpect(status().isNoContent());
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE clerk_user_id = ?", ALICE));
    }

    /** Rows per table with a user_id column, keyed by table. */
    private Map<String, Integer> rowsOf(UUID userId) {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.columns
                WHERE table_schema = 'PUBLIC' AND column_name = 'USER_ID'""", String.class);
        assertTrue(tables.size() >= 11, "Expected every user table, found " + tables);
        Map<String, Integer> rows = new TreeMap<>();
        for (String table : tables) {
            rows.put(table, count("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", userId));
        }
        return rows;
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    /** One of everything the app stores for a user, with IDs starting with {@code prefix}. */
    private UUID seedEverything(String clerkUserId, String prefix) {
        User user = new User(clerkUserId);
        entityManager.persist(user);
        entityManager.flush();
        UUID userId = user.getId();
        LocalDate today = LocalDate.now();
        String item = prefix + "-item";
        String account = prefix + "-checking";
        String transaction = prefix + "-coffee";

        entityManager.persist(new PlaidItem(item,
                tokenEncryption.encrypt(prefix + "-access-token", userId, item), userId));
        PlaidAccount checking = new PlaidAccount(account, item, userId);
        checking.updateSnapshot("Checking", null, "1234", "depository", "checking",
                new BigDecimal("900"), new BigDecimal("1000"), null, "USD", null);
        entityManager.persist(checking);
        BalanceSnapshot snapshot = new BalanceSnapshot(account, today, userId);
        snapshot.updateBalance(new BigDecimal("1000"));
        entityManager.persist(snapshot);
        entityManager.persist(new AccountDrop(account, userId, today.minusDays(10), today.minusDays(5)));
        entityManager.persist(new PlaidTransaction(transaction, item, userId, account, new BigDecimal("4.50"), today)
                .merchantName("Coffee"));
        entityManager.persist(new PlaidRecurringStream(
                prefix + "-stream", item, userId, account, new BigDecimal("15.49"), "MONTHLY", false));

        SpendingPlan plan = new SpendingPlan(userId);
        plan.replace(account, new BigDecimal("5000"), new BigDecimal("6000"), SpendingPlan.DEFAULT_BUFFER_PERCENT,
                List.of(new SpendingPlanLine(SpendingPlanBucket.FIXED_COSTS, "Subscriptions",
                        new BigDecimal("15.49"), false, null,
                        List.of(new SpendingPlanItem("Streaming", new BigDecimal("15.49"), prefix + "-stream")))));
        entityManager.persist(plan);

        PayeeCorrection correction = new PayeeCorrection(userId, "coffee");
        correction.correct(Bucket.GUILT_FREE, null, Instant.now());
        entityManager.persist(correction);
        RecurringAnswer answer = new RecurringAnswer(userId, RecurringKind.BILL, "streaming");
        answer.answer(true, Instant.now());
        entityManager.persist(answer);
        RecurringPayee payee = new RecurringPayee(userId, new RecurringMerchant(RecurringKind.BILL, "streaming"));
        payee.judged(null, null, SpendingPlanBucket.FIXED_COSTS, "Subscriptions", "state", Instant.now());
        entityManager.persist(payee);
        entityManager.persist(new TransactionCounting(transaction, userId));
        entityManager.flush();
        return userId;
    }
}
