package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.InvestmentsHoldingsGetRequest;
import com.plaid.client.model.InvestmentsHoldingsGetResponse;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.model.ItemRemoveRequest;
import com.plaid.client.model.ItemRemoveResponse;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.Products;
import com.plaid.client.model.LinkTokenCreateResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.TestPlaidKeysets;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaidItemLinkingTests {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String WEBHOOK_URL = "https://moneybuckets.test/api/plaid/webhook";

    @Mock
    private PlaidApi plaidApi;

    @Mock
    private PlaidItemRepository plaidItemRepository;

    @Mock
    private PlaidItemSync itemSync;

    @Mock
    private Call<LinkTokenCreateResponse> linkTokenCall;

    @Mock
    private Call<ItemPublicTokenExchangeResponse> exchangeCall;

    @Mock
    private Call<InvestmentsHoldingsGetResponse> holdingsCall;

    @Mock
    private Call<ItemRemoveResponse> removeCall;

    private final PlaidTokenEncryption tokenEncryption = new PlaidTokenEncryption(
            TestPlaidKeysets.create());
    private PlaidItemLinking linking;

    @BeforeEach
    void setUp() {
        linking = linking(WEBHOOK_URL);
    }

    @Test
    void pointsLinkTokensAtTheWebhookUrl() throws IOException {
        stubLinkToken();

        assertEquals("link-token", linking.createLinkToken(USER_ID));

        LinkTokenCreateRequest request = captureLinkTokenRequest();
        assertEquals(WEBHOOK_URL, request.getWebhook());
        assertEquals(USER_ID.toString(), request.getUser().getClientUserId());
    }

    @Test
    void everydayLinksAskForTransactions() throws IOException {
        stubLinkToken();

        linking.createLinkToken(USER_ID);

        LinkTokenCreateRequest request = captureLinkTokenRequest();
        assertEquals(List.of(Products.TRANSACTIONS), request.getProducts());
        assertNull(request.getOptionalProducts());
    }

    @Test
    void investmentLinksRequireInvestmentsAndAddTransactionsWhereSupported() throws IOException {
        stubLinkToken();

        assertEquals("link-token", linking.createInvestmentsLinkToken(USER_ID));

        LinkTokenCreateRequest request = captureLinkTokenRequest();
        assertEquals(List.of(Products.INVESTMENTS), request.getProducts());
        assertEquals(List.of(Products.TRANSACTIONS), request.getOptionalProducts());
        assertEquals(WEBHOOK_URL, request.getWebhook());
    }

    @Test
    void omitsTheWebhookUrlWhenItIsNotConfigured() throws IOException {
        stubLinkToken();

        linking("").createLinkToken(USER_ID);

        assertNull(captureLinkTokenRequest().getWebhook());
    }

    @Test
    void storesTheEncryptedAccessTokenAndSyncsTheItem() throws IOException {
        stubExchange("access-token");
        when(plaidItemRepository.findByItemIdAndUserId("item-id", USER_ID)).thenReturn(Optional.empty());

        assertEquals("item-id", linking.link(USER_ID, "public-token").itemId());

        ArgumentCaptor<ItemPublicTokenExchangeRequest> requestCaptor =
                ArgumentCaptor.forClass(ItemPublicTokenExchangeRequest.class);
        verify(plaidApi).itemPublicTokenExchange(requestCaptor.capture());
        assertEquals("public-token", requestCaptor.getValue().getPublicToken());

        PlaidItem saved = captureSavedItem();
        assertEquals("item-id", saved.getItemId());
        assertEquals(USER_ID, saved.getUserId());
        String encrypted = saved.getEncryptedAccessToken();
        assertNotEquals("access-token", encrypted);
        assertEquals("access-token", tokenEncryption.decrypt(encrypted, USER_ID, "item-id"));
        verify(itemSync).linked(saved);
    }

    @Test
    void keepsTheExistingCursorWhenAnItemIsRelinked() throws IOException {
        PlaidItem existing = new PlaidItem("item-id", "old-encrypted-token", USER_ID);
        ReflectionTestUtils.setField(existing, "transactionsCursor", "stored-cursor");
        stubExchange("new-access-token");
        when(plaidItemRepository.findByItemIdAndUserId("item-id", USER_ID)).thenReturn(Optional.of(existing));

        linking.link(USER_ID, "public-token");

        PlaidItem saved = captureSavedItem();
        assertEquals("stored-cursor", saved.getTransactionsCursor());
        assertEquals("new-access-token", tokenEncryption.decrypt(
                saved.getEncryptedAccessToken(), USER_ID, "item-id"));
    }

    @Test
    void listsTheUsersOtherItemsAtTheLinkedInstitution() throws IOException {
        stubExchange("access-token");
        PlaidItem linked = new PlaidItem("item-id", "encrypted", USER_ID);
        ReflectionTestUtils.setField(linked, "institutionId", "ins_fidelity");
        PlaidItem older = new PlaidItem("older-item", "encrypted", USER_ID);
        when(plaidItemRepository.findById("item-id")).thenReturn(Optional.of(linked));
        when(plaidItemRepository.findAllByUserIdAndInstitutionIdAndRemovedOnIsNull(USER_ID, "ins_fidelity"))
                .thenReturn(List.of(linked, older));

        assertEquals(List.of(older), linking.link(USER_ID, "public-token").sameInstitution());
    }

    @Test
    void addsInvestmentsToALinkedItemAndSyncsIt() throws IOException {
        PlaidItem item = storedItem();
        ReflectionTestUtils.setField(item, "investmentsAvailable", true);
        when(plaidApi.investmentsHoldingsGet(any(InvestmentsHoldingsGetRequest.class))).thenReturn(holdingsCall);
        when(holdingsCall.execute()).thenReturn(Response.success(new InvestmentsHoldingsGetResponse()));

        assertEquals(PlaidItemLinking.AddInvestments.added(), linking.addInvestments(USER_ID, "item-id"));

        verify(itemSync).linked(item);
    }

    @Test
    void asksForConsentInLinkUpdateModeWhenPlaidRefusesInvestments() throws IOException {
        ReflectionTestUtils.setField(storedItem(), "investmentsAvailable", true);
        when(plaidApi.investmentsHoldingsGet(any(InvestmentsHoldingsGetRequest.class))).thenReturn(holdingsCall);
        when(holdingsCall.execute()).thenReturn(
                Response.error(400, ResponseBody.create("{}", MediaType.get("application/json"))));
        stubLinkToken();

        assertEquals(PlaidItemLinking.AddInvestments.needsConsent("link-token"),
                linking.addInvestments(USER_ID, "item-id"));

        LinkTokenCreateRequest request = captureLinkTokenRequest();
        assertEquals("access-token", request.getAccessToken());
        assertEquals(List.of(Products.INVESTMENTS), request.getAdditionalConsentedProducts());
        verifyNoInteractions(itemSync);
    }

    @Test
    void refusesInstitutionsThatDoNotOfferInvestments() {
        PlaidItem bank = storedItem();
        ReflectionTestUtils.setField(bank, "investmentsAvailable", false);

        assertEquals(PlaidItemLinking.AddInvestments.notOffered(), linking.addInvestments(USER_ID, "item-id"));

        verifyNoInteractions(plaidApi, itemSync);
    }

    @Test
    void refreshesAnItemSyncedBeforeWeTrackedWhetherItOffersInvestments() {
        PlaidItem bank = storedItem();
        doAnswer(invocation -> {
            ReflectionTestUtils.setField(bank, "investmentsAvailable", false);
            return null;
        }).when(itemSync).refreshAccounts("item-id");

        assertEquals(PlaidItemLinking.AddInvestments.notOffered(), linking.addInvestments(USER_ID, "item-id"));

        verify(itemSync).refreshAccounts("item-id");
        verifyNoInteractions(plaidApi);
    }

    @Test
    void removesAnItemFromPlaidBeforeForgettingIt() throws IOException {
        storedItem();
        when(plaidApi.itemRemove(any(ItemRemoveRequest.class))).thenReturn(removeCall);
        when(removeCall.execute()).thenReturn(Response.success(new ItemRemoveResponse()));

        linking.remove(USER_ID, "item-id");

        ArgumentCaptor<ItemRemoveRequest> captor = ArgumentCaptor.forClass(ItemRemoveRequest.class);
        verify(plaidApi).itemRemove(captor.capture());
        assertEquals("access-token", captor.getValue().getAccessToken());
        verify(itemSync).removed("item-id");
    }

    @Test
    void keepsAnItemPlaidFailedToRemove() throws IOException {
        storedItem();
        when(plaidApi.itemRemove(any(ItemRemoveRequest.class))).thenReturn(removeCall);
        when(removeCall.execute()).thenThrow(new IOException("Plaid unreachable"));

        assertThrows(PlaidRequestException.class, () -> linking.remove(USER_ID, "item-id"));

        verifyNoInteractions(itemSync);
    }

    @Test
    void cannotChangeAnItemTheUserDoesNotHave() {
        assertThrows(NoSuchElementException.class, () -> linking.addInvestments(USER_ID, "item-id"));
        assertThrows(NoSuchElementException.class, () -> linking.remove(USER_ID, "item-id"));

        verifyNoInteractions(plaidApi, itemSync);
    }

    @Test
    void storesNothingWhenPlaidRejectsTheExchange() throws IOException {
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class)))
                .thenReturn(exchangeCall);
        when(exchangeCall.execute()).thenReturn(
                Response.error(500, ResponseBody.create("{}", MediaType.get("application/json"))));

        assertThrows(PlaidRequestException.class, () -> linking.link(USER_ID, "public-token"));

        verifyNoInteractions(plaidItemRepository, itemSync);
    }

    private PlaidItem storedItem() {
        PlaidItem item = new PlaidItem(
                "item-id", tokenEncryption.encrypt("access-token", USER_ID, "item-id"), USER_ID);
        when(plaidItemRepository.findByItemIdAndUserIdAndRemovedOnIsNull("item-id", USER_ID))
                .thenReturn(Optional.of(item));
        return item;
    }

    private PlaidItemLinking linking(String webhookUrl) {
        return new PlaidItemLinking(plaidApi, plaidItemRepository, tokenEncryption, itemSync, webhookUrl);
    }

    private void stubLinkToken() throws IOException {
        when(plaidApi.linkTokenCreate(any(LinkTokenCreateRequest.class))).thenReturn(linkTokenCall);
        when(linkTokenCall.execute()).thenReturn(
                Response.success(new LinkTokenCreateResponse().linkToken("link-token")));
    }

    private LinkTokenCreateRequest captureLinkTokenRequest() {
        ArgumentCaptor<LinkTokenCreateRequest> captor = ArgumentCaptor.forClass(LinkTokenCreateRequest.class);
        verify(plaidApi).linkTokenCreate(captor.capture());
        return captor.getValue();
    }

    private void stubExchange(String accessToken) throws IOException {
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class)))
                .thenReturn(exchangeCall);
        when(exchangeCall.execute()).thenReturn(Response.success(new ItemPublicTokenExchangeResponse()
                .itemId("item-id")
                .accessToken(accessToken)
                .requestId("request-id")));
    }

    private PlaidItem captureSavedItem() {
        ArgumentCaptor<PlaidItem> captor = ArgumentCaptor.forClass(PlaidItem.class);
        verify(plaidItemRepository).save(captor.capture());
        return captor.getValue();
    }
}
