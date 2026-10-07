package dev.matthewsawyer.finance_dashboard;

import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.LinkTokenCreateResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.BalanceSnapshot;
import dev.matthewsawyer.finance_dashboard.model.Bucket;
import dev.matthewsawyer.finance_dashboard.model.PayeeCorrection;
import dev.matthewsawyer.finance_dashboard.model.PlaidAccount;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.PlaidRecurringStream;
import dev.matthewsawyer.finance_dashboard.model.PlaidTransaction;
import dev.matthewsawyer.finance_dashboard.model.RecurringAnswer;
import dev.matthewsawyer.finance_dashboard.model.RecurringKind;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlan;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanBucket;
import dev.matthewsawyer.finance_dashboard.model.SpendingPlanLine;
import dev.matthewsawyer.finance_dashboard.model.TransactionCounting;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.BalanceSnapshotRepository;
import dev.matthewsawyer.finance_dashboard.repository.PayeeCorrectionRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidAccountRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidRecurringStreamRepository;
import dev.matthewsawyer.finance_dashboard.repository.PlaidTransactionRepository;
import dev.matthewsawyer.finance_dashboard.repository.RecurringAnswerRepository;
import dev.matthewsawyer.finance_dashboard.repository.SpendingPlanRepository;
import dev.matthewsawyer.finance_dashboard.repository.TransactionCountingRepository;
import dev.matthewsawyer.finance_dashboard.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import retrofit2.Call;
import retrofit2.Response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Alice has one of everything the app stores, and Bob signs in. Every endpoint is called as Bob,
 * with Alice's IDs wherever it takes one, and must neither show him her data nor change it.
 * Every name and ID of Alice's contains "alice", so a response that leaks any of it is caught
 * by looking for that word.
 */
@SpringBootTest(properties = {
        "PLAID_CLIENT_ID=test-client-id",
        "PLAID_SANDBOX_SECRET=test-secret"
})
@AutoConfigureMockMvc
@Transactional
class UserSeparationTests {

    private static final String ALICE = "user_alice";
    private static final String BOB = "user_bob";
    private static final String ITEM = "alice-item";
    private static final String ACCOUNT = "alice-checking";
    private static final String TRANSACTION = "alice-coffee";
    private static final String PAYEE = "alicecorp coffee";
    private static final String STREAMING = "alicecorp streaming";

    /**
     * Every endpoint, and every one is covered below. A new endpoint fails
     * {@link #everyEndpointIsCovered} until it gets a check here.
     */
    private static final Set<String> ENDPOINTS = Set.of(
            "GET /users/me",
            "POST /plaid/create-link-token",
            "POST /plaid/items",
            "GET /plaid/items",
            "POST /plaid/items/{itemId}/investments",
            "DELETE /plaid/items/{itemId}",
            "GET /plaid/accounts",
            "PUT /plaid/accounts/{accountId}/tracking",
            "GET /plaid/transactions",
            "GET /plaid/transactions/recurring",
            "POST /plaid/transactions/recurring/sync",
            "GET /plaid/transactions/recurring/candidates",
            "PUT /plaid/transactions/recurring/candidates/answer",
            "DELETE /plaid/transactions/recurring/candidates/answer",
            "GET /plaid/spending/by-bucket",
            "GET /investments/history",
            "GET /spending-plan",
            "PUT /spending-plan",
            "PUT /payees/corrections",
            "DELETE /payees/corrections",
            "PUT /transactions/counting",
            "POST /plaid/webhook");

    /** Signed by Plaid instead of a user, and checked in PlaidWebhookControllerTests. */
    private static final String WEBHOOK = "POST /plaid/webhook";

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

    @Autowired private MockMvc mockMvc;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;
    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository users;
    @Autowired private PlaidItemRepository items;
    @Autowired private PlaidAccountRepository accounts;
    @Autowired private PlaidTransactionRepository transactions;
    @Autowired private PlaidRecurringStreamRepository streams;
    @Autowired private BalanceSnapshotRepository snapshots;
    @Autowired private SpendingPlanRepository plans;
    @Autowired private PayeeCorrectionRepository corrections;
    @Autowired private RecurringAnswerRepository answers;
    @Autowired private TransactionCountingRepository countings;

    private UUID aliceId;

    @BeforeEach
    void seedAlice() {
        aliceId = users.saveAndFlush(new User(ALICE)).getId();
        LocalDate today = LocalDate.now();

        PlaidItem item = new PlaidItem(ITEM, "alice-encrypted-token", aliceId);
        items.save(item);

        PlaidAccount account = new PlaidAccount(ACCOUNT, ITEM, aliceId);
        account.updateSnapshot("Alice Checking", null, "1234", "depository", "checking",
                new BigDecimal("900"), new BigDecimal("1000"), null, "USD", null);
        account.updateTracking(true, true, 100);
        accounts.save(account);

        BalanceSnapshot snapshot = new BalanceSnapshot(ACCOUNT, today, aliceId);
        snapshot.updateBalance(new BigDecimal("1000"));
        snapshots.save(snapshot);

        transactions.save(new PlaidTransaction(TRANSACTION, ITEM, aliceId, ACCOUNT, new BigDecimal("4.50"), today)
                .name("ALICECORP COFFEE")
                .merchantName("Alicecorp Coffee")
                .personalFinanceCategory("FOOD_AND_DRINK", "FOOD_AND_DRINK_COFFEE"));
        streams.save(new PlaidRecurringStream(
                "alice-stream", ITEM, aliceId, ACCOUNT, new BigDecimal("15.49"), "MONTHLY", false)
                .merchantName("Alicecorp Streaming"));

        SpendingPlan plan = new SpendingPlan(aliceId);
        plan.replace(ACCOUNT, new BigDecimal("5000"), new BigDecimal("6000"), SpendingPlan.DEFAULT_BUFFER_PERCENT,
                List.of(new SpendingPlanLine(SpendingPlanBucket.FIXED_COSTS, "Alice's rent",
                        new BigDecimal("1500"), false, null, List.of())));
        plans.save(plan);

        PayeeCorrection correction = new PayeeCorrection(aliceId, PAYEE);
        correction.correct(Bucket.GUILT_FREE, null, Instant.now());
        corrections.save(correction);

        RecurringAnswer answer = new RecurringAnswer(aliceId, RecurringKind.BILL, STREAMING);
        answer.answer(true, Instant.now());
        answers.save(answer);
        // Makes her coffee a recurring candidate, which needs a payee Plaid doesn't detect.
        RecurringAnswer coffee = new RecurringAnswer(aliceId, RecurringKind.BILL, PAYEE);
        coffee.answer(true, Instant.now());
        answers.save(coffee);

        countings.save(new TransactionCounting(TRANSACTION, aliceId));

        entityManager.flush();
        transactions.setBuckets(List.of(TRANSACTION), Bucket.GUILT_FREE, Instant.now());
        entityManager.clear();
    }

    @Test
    void everyEndpointIsCovered() {
        Set<String> mapped = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (var method : info.getMethodsCondition().getMethods()) {
                for (String path : info.getPatternValues()) {
                    mapped.add(method + " " + path);
                }
            }
        }
        mapped.removeIf(endpoint -> endpoint.contains("/error"));

        assertEquals(new TreeSet<>(ENDPOINTS), mapped,
                "Every endpoint needs a check in UserSeparationTests that it keeps users apart");
    }

    static Stream<String> endpointsForUsers() {
        return ENDPOINTS.stream().filter(endpoint -> !endpoint.equals(WEBHOOK)).sorted();
    }

    @ParameterizedTest
    @MethodSource("endpointsForUsers")
    void turnsAwayRequestsWithoutAUser(String endpoint) throws Exception {
        String[] parts = endpoint.split(" ");
        String path = parts[1].replace("{itemId}", ITEM).replace("{accountId}", ACCOUNT);

        mockMvc.perform(request(HttpMethod.valueOf(parts[0]), path))
                .andExpect(status().isUnauthorized());
    }

    /** Endpoints that show the signed-in user's data, each with Alice's account where it takes one. */
    static Stream<String> reads() {
        return Stream.of(
                "/users/me",
                "/plaid/items",
                "/plaid/accounts",
                "/plaid/transactions",
                "/plaid/transactions?account_id=" + ACCOUNT,
                "/plaid/transactions/recurring",
                "/plaid/transactions/recurring?account_id=" + ACCOUNT + "&dismissed=true",
                "/plaid/transactions/recurring/candidates",
                "/plaid/transactions/recurring/candidates?account_id=" + ACCOUNT,
                "/plaid/spending/by-bucket",
                "/plaid/spending/by-bucket?account_id=" + ACCOUNT,
                "/investments/history",
                "/spending-plan");
    }

    @ParameterizedTest
    @MethodSource("reads")
    void showsBobNoneOfAlicesData(String path) throws Exception {
        assertTrue(body(as(ALICE, HttpMethod.GET, path), status().isOk()).contains("alice"),
                "Alice should see her own data at " + path + ", or this test proves nothing");

        // Bob has no plan of his own to show.
        String bobSees = body(as(BOB, HttpMethod.GET, path),
                path.equals("/spending-plan") ? status().isNotFound() : status().isOk());

        assertFalse(bobSees.contains("alice"), "Bob saw Alice's data at " + path + ": " + bobSees);
    }

    @Test
    void doesNotLetBobRemoveAlicesBank() throws Exception {
        mockMvc.perform(as(BOB, HttpMethod.DELETE, "/plaid/items/" + ITEM)).andExpect(status().isNotFound());

        assertFalse(items.findById(ITEM).orElseThrow().isRemoved());
        verifyNoInteractions(plaidApi);
    }

    @Test
    void doesNotLetBobAddInvestmentsToAlicesBank() throws Exception {
        mockMvc.perform(as(BOB, HttpMethod.POST, "/plaid/items/" + ITEM + "/investments"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(plaidApi);
    }

    @Test
    void doesNotLetBobChangeHowAlicesAccountCounts() throws Exception {
        mockMvc.perform(as(BOB, HttpMethod.PUT, "/plaid/accounts/" + ACCOUNT + "/tracking")
                        .content("""
                                {"tracks_spending": false, "counts_in_net_worth": false, "share_percent": 50}
                                """))
                .andExpect(status().isNotFound());

        PlaidAccount account = accounts.findByAccountIdAndUserId(ACCOUNT, aliceId).orElseThrow();
        assertTrue(account.tracksSpending());
        assertEquals(100, account.getSharePercent());
    }

    @Test
    void syncsOnlyBobsOwnBanks() throws Exception {
        mockMvc.perform(as(BOB, HttpMethod.POST, "/plaid/transactions/recurring/sync"))
                .andExpect(status().is2xxSuccessful());

        verifyNoInteractions(plaidApi);
    }

    @Test
    @SuppressWarnings("unchecked")
    void opensPlaidLinkForBobHimself() throws Exception {
        Call<LinkTokenCreateResponse> call = mock(Call.class);
        when(plaidApi.linkTokenCreate(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(new LinkTokenCreateResponse().linkToken("link-token")));

        mockMvc.perform(as(BOB, HttpMethod.POST, "/plaid/create-link-token")).andExpect(status().isOk());

        ArgumentCaptor<LinkTokenCreateRequest> request = ArgumentCaptor.forClass(LinkTokenCreateRequest.class);
        org.mockito.Mockito.verify(plaidApi).linkTokenCreate(request.capture());
        assertEquals(users.findByClerkUserId(BOB).orElseThrow().getId().toString(),
                request.getValue().getUser().getClientUserId());
    }

    @Test
    void refusesALinkWithoutAPublicToken() throws Exception {
        mockMvc.perform(as(BOB, HttpMethod.POST, "/plaid/items").content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(plaidApi);
    }

    /**
     * Linking stores whatever item Plaid hands back for the public token under the signed-in
     * user. Bob can't name Alice's item: Plaid only issues the token to the browser that went
     * through Link.
     */
    @Test
    @SuppressWarnings("unchecked")
    void storesBobsLinkedBankUnderBob() throws Exception {
        Call<ItemPublicTokenExchangeResponse> call = mock(Call.class);
        when(plaidApi.itemPublicTokenExchange(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(
                new ItemPublicTokenExchangeResponse().accessToken("bob-access-token").itemId("bob-item")));
        // The initial sync's Plaid calls are left unstubbed; their failures don't fail the link.

        mockMvc.perform(as(BOB, HttpMethod.POST, "/plaid/items")
                        .content("""
                                {"publicToken": "bob-public-token"}
                                """))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        assertEquals(bobId(), items.findById("bob-item").orElseThrow().getUserId());
        assertEquals(aliceId, items.findById(ITEM).orElseThrow().getUserId());
    }

    @Test
    void doesNotLetBobChangeAlicesPlan() throws Exception {
        mockMvc.perform(as(BOB, HttpMethod.PUT, "/spending-plan")
                        .content("""
                                {"account_id": "%s", "take_home": 1, "lines": []}
                                """.formatted(ACCOUNT)))
                .andExpect(status().isBadRequest());

        SpendingPlan plan = plans.findByUserId(aliceId).orElseThrow();
        assertEquals(0, new BigDecimal("5000").compareTo(plan.getTakeHome()));
        assertTrue(plans.findByUserId(bobId()).isEmpty());
    }

    @Test
    void keepsBobsPayeeCorrectionsOffAlicesTransactions() throws Exception {
        mockMvc.perform(as(BOB, HttpMethod.PUT, "/payees/corrections")
                        .content("""
                                {"merchant_key": "%s", "bucket": "FIXED_COSTS"}
                                """.formatted(PAYEE)))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(as(BOB, HttpMethod.DELETE, "/payees/corrections?merchant_key=" + PAYEE.replace(' ', '+')))
                .andExpect(status().is2xxSuccessful());
        entityManager.clear();

        assertEquals(Bucket.GUILT_FREE, corrections.findByUserIdAndMerchantKey(aliceId, PAYEE).orElseThrow().getBucket());
        assertEquals(Bucket.GUILT_FREE, transactions.findById(TRANSACTION).orElseThrow().getBucket());
    }

    @Test
    void keepsBobsRecurringAnswersSeparate() throws Exception {
        mockMvc.perform(as(BOB, HttpMethod.PUT, "/plaid/transactions/recurring/candidates/answer")
                        .content("""
                                {"kind": "BILL", "merchant_key": "%s", "confirmed": false}
                                """.formatted(STREAMING)))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(as(BOB, HttpMethod.DELETE, "/plaid/transactions/recurring/candidates/answer?kind=BILL&merchant_key="
                        + STREAMING.replace(' ', '+')))
                .andExpect(status().is2xxSuccessful());
        entityManager.clear();

        assertTrue(answers.findByUserIdAndKindAndMerchantKey(aliceId, RecurringKind.BILL, STREAMING)
                .orElseThrow().isConfirmed());
    }

    @Test
    void doesNotLetBobExcludeAlicesTransaction() throws Exception {
        mockMvc.perform(as(BOB, HttpMethod.PUT, "/transactions/counting")
                        .content("""
                                {"transaction_id": "%s", "excluded": true, "future": true}
                                """.formatted(TRANSACTION)))
                .andExpect(status().isNotFound());
        entityManager.clear();

        TransactionCounting counting = countings.findById(TRANSACTION).orElseThrow();
        assertFalse(counting.isExcluded());
        assertEquals(aliceId, counting.getUserId());
    }

    private MockHttpServletRequestBuilder as(String user, HttpMethod method, String path) {
        return request(method, path)
                .header("Authorization", "Bearer " + user)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String body(MockHttpServletRequestBuilder request, ResultMatcher expectedStatus) throws Exception {
        return mockMvc.perform(request)
                .andExpect(expectedStatus)
                .andReturn().getResponse().getContentAsString()
                .toLowerCase(Locale.ROOT);
    }

    private UUID bobId() {
        return users.findByClerkUserId(BOB).map(User::getId).orElseThrow();
    }
}
