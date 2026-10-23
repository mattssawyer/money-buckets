package dev.matthewsawyer.finance_dashboard.plaid;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import com.plaid.client.model.ItemAccessTokenInvalidateRequest;
import com.plaid.client.model.ItemAccessTokenInvalidateResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.TestPlaidKeysets;
import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlaidAccessTokenRefreshTests {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final PlaidApi plaidApi = mock(PlaidApi.class);
    private final PlaidItemRepository items = mock(PlaidItemRepository.class);
    private final PlaidItemLocks locks = mock(PlaidItemLocks.class);

    private String oldKeyset;
    private PlaidTokenEncryption oldEncryption;
    private PlaidTokenEncryption rotatedEncryption;

    @BeforeEach
    void setUp() throws Exception {
        oldKeyset = TestPlaidKeysets.create();
        oldEncryption = new PlaidTokenEncryption(oldKeyset);
        KeysetHandle rotated = KeysetHandle.newBuilder(
                        TinkJsonProtoKeysetFormat.parseKeyset(oldKeyset, InsecureSecretKeyAccess.get()))
                .addEntry(KeysetHandle.generateEntryFromParameters(PredefinedAeadParameters.AES256_GCM)
                        .withRandomId().makePrimary())
                .build();
        rotatedEncryption = new PlaidTokenEncryption(
                TinkJsonProtoKeysetFormat.serializeKeyset(rotated, InsecureSecretKeyAccess.get()));
        doAnswer(invocation -> { invocation.<Runnable>getArgument(2).run(); return true; })
                .when(locks).run(any(), any(), any());
    }

    @Test
    void replacesALinkedItemsTokenAndEncryptsItWithTheNewKey() throws Exception {
        PlaidItem item = item("item-1", "old-token");
        when(items.findAll()).thenReturn(List.of(item));
        stubInvalidate(Response.success(new ItemAccessTokenInvalidateResponse().newAccessToken("new-token")));

        PlaidAccessTokenRefresh.Summary summary = refresh().refreshAll();

        assertEquals(new PlaidAccessTokenRefresh.Summary(1, 0, 0), summary);
        ArgumentCaptor<ItemAccessTokenInvalidateRequest> request =
                ArgumentCaptor.forClass(ItemAccessTokenInvalidateRequest.class);
        verify(plaidApi).itemAccessTokenInvalidate(request.capture());
        assertEquals("old-token", request.getValue().getAccessToken());
        String stored = storedToken("item-1");
        assertEquals("new-token", rotatedEncryption.decrypt(stored, USER_ID, "item-1"));
        assertNotReadableWithOldKey(stored, "item-1");
    }

    @Test
    void reEncryptsARemovedItemWithoutAskingPlaid() {
        PlaidItem item = item("item-1", "old-token");
        ReflectionTestUtils.setField(item, "removedOn", LocalDate.of(2026, 10, 1));
        when(items.findAll()).thenReturn(List.of(item));

        PlaidAccessTokenRefresh.Summary summary = refresh().refreshAll();

        assertEquals(new PlaidAccessTokenRefresh.Summary(0, 1, 0), summary);
        verifyNoInteractions(plaidApi);
        String stored = storedToken("item-1");
        assertEquals("old-token", rotatedEncryption.decrypt(stored, USER_ID, "item-1"));
        assertNotReadableWithOldKey(stored, "item-1");
    }

    @Test
    void keepsTheTokenButReEncryptsItWhenPlaidRefuses() throws Exception {
        PlaidItem item = item("item-1", "old-token");
        when(items.findAll()).thenReturn(List.of(item));
        stubInvalidate(Response.error(400, ResponseBody.create(
                "{\"error_code\":\"ITEM_LOGIN_REQUIRED\"}", MediaType.get("application/json"))));

        PlaidAccessTokenRefresh.Summary summary = refresh().refreshAll();

        assertEquals(new PlaidAccessTokenRefresh.Summary(0, 1, 0), summary);
        assertEquals("old-token", rotatedEncryption.decrypt(storedToken("item-1"), USER_ID, "item-1"));
    }

    @Test
    void carriesOnPastAnItemItCannotDecrypt() throws Exception {
        PlaidItem unreadable = new PlaidItem("item-1", "not-a-ciphertext", USER_ID);
        PlaidItem readable = item("item-2", "old-token");
        when(items.findAll()).thenReturn(List.of(unreadable, readable));
        stubInvalidate(Response.success(new ItemAccessTokenInvalidateResponse().newAccessToken("new-token")));

        PlaidAccessTokenRefresh.Summary summary = refresh().refreshAll();

        assertEquals(new PlaidAccessTokenRefresh.Summary(1, 0, 1), summary);
        verify(items, never()).updateEncryptedAccessToken(eq("item-1"), any());
    }

    @Test
    void countsAnItemWhoseLockItCannotGetAsFailed() {
        when(items.findAll()).thenReturn(List.of(item("item-1", "old-token")));
        doReturn(false).when(locks).run(any(), any(), any());

        assertEquals(new PlaidAccessTokenRefresh.Summary(0, 0, 1), refresh().refreshAll());
        verifyNoInteractions(plaidApi);
    }

    @Test
    void countsAFailedSaveAsFailed() throws Exception {
        when(items.findAll()).thenReturn(List.of(item("item-1", "old-token")));
        stubInvalidate(Response.success(new ItemAccessTokenInvalidateResponse().newAccessToken("new-token")));
        doThrow(new IllegalStateException("database unavailable"))
                .when(items).updateEncryptedAccessToken(eq("item-1"), any());

        assertEquals(new PlaidAccessTokenRefresh.Summary(0, 0, 1), refresh().refreshAll());
    }

    @Test
    void doesNothingOnStartUnlessTurnedOn() {
        new PlaidAccessTokenRefresh(false, plaidApi, items, rotatedEncryption, locks).onStart();

        verifyNoInteractions(items, plaidApi);
    }

    private PlaidAccessTokenRefresh refresh() {
        return new PlaidAccessTokenRefresh(true, plaidApi, items, rotatedEncryption, locks);
    }

    private PlaidItem item(String itemId, String token) {
        return new PlaidItem(itemId, oldEncryption.encrypt(token, USER_ID, itemId), USER_ID);
    }

    @SuppressWarnings("unchecked")
    private void stubInvalidate(Response<ItemAccessTokenInvalidateResponse> response) throws Exception {
        Call<ItemAccessTokenInvalidateResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(response);
        when(plaidApi.itemAccessTokenInvalidate(any())).thenReturn(call);
    }

    private String storedToken(String itemId) {
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(items).updateEncryptedAccessToken(eq(itemId), stored.capture());
        return stored.getValue();
    }

    private void assertNotReadableWithOldKey(String stored, String itemId) {
        assertThrows(IllegalStateException.class, () -> oldEncryption.decrypt(stored, USER_ID, itemId));
    }
}
