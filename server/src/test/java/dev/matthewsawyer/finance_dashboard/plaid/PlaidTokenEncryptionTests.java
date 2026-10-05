package dev.matthewsawyer.finance_dashboard.plaid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.matthewsawyer.finance_dashboard.TestPlaidKeysets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlaidTokenEncryptionTests {
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String keysetJson = TestPlaidKeysets.create();
    private final PlaidTokenEncryption encryption = new PlaidTokenEncryption(keysetJson);

    @Test
    void roundTripsWithDifferentCiphertextForEveryWrite() {
        String first = encryption.encrypt("access-sandbox-token", USER_ID, "item");
        String second = encryption.encrypt("access-sandbox-token", USER_ID, "item");
        assertNotEquals(first, second);
        assertFalse(first.contains("access-sandbox-token"));
        assertEquals("access-sandbox-token", encryption.decrypt(first, USER_ID, "item"));
        assertEquals("access-sandbox-token", encryption.decrypt(second, USER_ID, "item"));
        assertEquals("access-sandbox-token",
                new PlaidTokenEncryption(keysetJson).decrypt(first, USER_ID, "item"));
    }

    @Test
    void rejectsTamperedCiphertext() {
        String stored = encryption.encrypt("access-sandbox-token", USER_ID, "item");
        byte[] original = Base64.getDecoder().decode(stored);
        for (int position : new int[] {0, original.length / 2, original.length - 1}) {
            byte[] modified = original.clone();
            modified[position] ^= 1;
            String tampered = Base64.getEncoder().encodeToString(modified);
            assertThrows(IllegalStateException.class, () -> encryption.decrypt(tampered, USER_ID, "item"));
        }
    }

    @Test
    void rejectsWrongKeyOrDifferentOwnerOrItem() {
        String stored = encryption.encrypt("access-sandbox-token", USER_ID, "item");
        PlaidTokenEncryption other = new PlaidTokenEncryption(TestPlaidKeysets.create());
        assertThrows(IllegalStateException.class, () -> other.decrypt(stored, USER_ID, "item"));
        assertThrows(IllegalStateException.class, () -> encryption.decrypt(stored, UUID.randomUUID(), "item"));
        assertThrows(IllegalStateException.class, () -> encryption.decrypt(stored, USER_ID, "other-item"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"access-plaintext", "v2:abcd", "v1:!", "v1:YWJjZA=="})
    void rejectsMalformedOrUnencryptedValuesWithoutLeakingThem(String stored) {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> encryption.decrypt(stored, USER_ID, "item"));
        assertEquals("Unable to decrypt Plaid access token", error.getMessage());
        assertNull(error.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json!", "{}", "{\"primaryKeyId\":1,\"key\":[]}", "AAAAAAAAAAAAAAAAAAAAAA=="})
    void rejectsMissingMalformedOrEmptyKeysets(String key) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PlaidTokenEncryption(key));
        assertEquals("PLAID_TOKEN_ENCRYPTION_KEYSET must be a valid Tink AEAD JSON keyset", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void generatesAKeysetFileTheFirstTimeAndKeepsUsingIt(@TempDir Path dir) {
        Path file = dir.resolve("keys/plaid-keyset.json");
        String stored = new PlaidTokenEncryption("", file.toString()).encrypt("access-sandbox-token", USER_ID, "item");

        assertTrue(Files.exists(file));
        assertEquals("access-sandbox-token",
                new PlaidTokenEncryption("", file.toString()).decrypt(stored, USER_ID, "item"));
    }

    @Test
    void prefersTheKeysetSetDirectlyOverTheFile(@TempDir Path dir) {
        Path file = dir.resolve("plaid-keyset.json");
        String stored = encryption.encrypt("access-sandbox-token", USER_ID, "item");

        assertEquals("access-sandbox-token",
                new PlaidTokenEncryption(keysetJson, file.toString()).decrypt(stored, USER_ID, "item"));
        assertFalse(Files.exists(file));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsEmptyTokens(String token) {
        assertThrows(IllegalArgumentException.class, () -> encryption.encrypt(token, USER_ID, "item"));
    }
}
