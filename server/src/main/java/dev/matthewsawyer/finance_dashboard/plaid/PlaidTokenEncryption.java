package dev.matthewsawyer.finance_dashboard.plaid;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
public class PlaidTokenEncryption {

    private final Aead aead;

    /**
     * Uses the keyset in {@code PLAID_TOKEN_ENCRYPTION_KEYSET} when it's set. Otherwise, when
     * {@code PLAID_TOKEN_ENCRYPTION_KEYSET_FILE} names a file, uses the keyset in it, generating
     * one there the first time, so self-hosting needs no keyset made by hand.
     */
    @Autowired
    public PlaidTokenEncryption(
            @Value("${PLAID_TOKEN_ENCRYPTION_KEYSET:}") String keysetJson,
            @Value("${PLAID_TOKEN_ENCRYPTION_KEYSET_FILE:}") String keysetFile
    ) {
        this(keysetJson.isBlank() && !keysetFile.isBlank() ? keysetFrom(Path.of(keysetFile)) : keysetJson);
    }

    public PlaidTokenEncryption(String keysetJson) {
        try {
            AeadConfig.register();
            // Railway protects the secret at rest and supplies its JSON at runtime.
            // This API explicitly acknowledges access to unwrapped secret key material.
            aead = TinkJsonProtoKeysetFormat.parseKeyset(keysetJson, InsecureSecretKeyAccess.get())
                    .getPrimitive(RegistryConfiguration.get(), Aead.class);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Parser errors may include secret input, so do not propagate their causes.
            throw new IllegalArgumentException("PLAID_TOKEN_ENCRYPTION_KEYSET must be a valid Tink AEAD JSON keyset");
        }
    }

    public String encrypt(String token, UUID userId, String itemId) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Plaid access token must not be empty");
        }
        try {
            return Base64.getEncoder().encodeToString(
                    aead.encrypt(token.getBytes(UTF_8), context(userId, itemId)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt Plaid access token");
        }
    }

    public String decrypt(String storedToken, UUID userId, String itemId) {
        if (storedToken == null) {
            throw new IllegalStateException("Unable to decrypt Plaid access token");
        }
        try {
            return new String(aead.decrypt(
                    Base64.getDecoder().decode(storedToken), context(userId, itemId)), UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to decrypt Plaid access token");
        }
    }

    /** Reads the keyset in {@code file}, generating it first if there's none yet. */
    static String keysetFrom(Path file) {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                AeadConfig.register();
                String generated = TinkJsonProtoKeysetFormat.serializeKeyset(
                        KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM), InsecureSecretKeyAccess.get());
                Files.writeString(file, generated, StandardOpenOption.CREATE_NEW);
                // Readable only by the server's own user.
                file.toFile().setReadable(false, false);
                file.toFile().setReadable(true, true);
            }
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read or create the Plaid token keyset file " + file, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to generate a Plaid token keyset");
        }
    }

    private static byte[] context(UUID userId, String itemId) {
        return ("plaid-access-token:" + userId + ":" + itemId).getBytes(UTF_8);
    }
}
