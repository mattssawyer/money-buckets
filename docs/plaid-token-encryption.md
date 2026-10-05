# Plaid access-token encryption

The backend uses [Google Tink](https://developers.google.com/tink/encrypt-data)
for authenticated encryption. Tink handles keys, nonces, authentication tags and
ciphertext formatting. The application calls `Aead.encrypt` / `Aead.decrypt` and
Base64-encodes the result for the database. The user and Plaid item IDs are supplied
as associated data, so a token cannot be moved to a different user or item.

### Create a keyset

Use Google's standard [Tinkey CLI](https://developers.google.com/tink/tinkey-overview).
On macOS:

```sh
brew tap tink-crypto/tink-tinkey https://github.com/tink-crypto/tink-tinkey
brew install tinkey
umask 077
tinkey create-keyset --key-template AES256_GCM --out-format json --out plaid-production-keyset.json
```

The JSON file contains secret key material. Keep a recovery copy in a password
manager, keep it out of Git and remove the temporary file after configuring Railway.
The repository ignores `plaid-*-keyset.json` as an additional safeguard.

### Railway

1. Add `PLAID_TOKEN_ENCRYPTION_KEYSET` to the **backend service**. Paste the entire
   generated JSON, including braces, as its value. Multiline JSON is supported.
2. Seal the variable, then deploy the backend changes and the variable together.
3. Remove the obsolete `PLAID_TOKEN_ENCRYPTION_KEY` variable; it is no longer read.

Railway supplies the secret at runtime. Tink's `InsecureSecretKeyAccess` API
explicitly acknowledges that this JSON contains unwrapped secret keys; protection
at rest is provided by Railway's secret storage, not by an additional application
key. The keyset never belongs in Postgres or frontend variables.

Startup fails for a missing or invalid keyset. Sealed variables are not supplied
by `railway run` or copied to preview environments; give each environment its own
keyset. There is no connection to Google Cloud and no extra hosted service.

### Self-hosting with Docker

With `PLAID_TOKEN_ENCRYPTION_KEYSET` empty, the server reads its keyset from the file named
by `PLAID_TOKEN_ENCRYPTION_KEYSET_FILE`, generating one there on first start. The Docker
Compose file points it into the `server-data` volume; see the README for backing it up.
A keyset set directly always wins over the file.

### Local development

Generate a separate keyset with Tinkey using the same command but output to
`plaid-local-keyset.json`. Then, from `server/`, export it before starting:

```sh
export PLAID_TOKEN_ENCRYPTION_KEYSET="$(cat /path/to/plaid-local-keyset.json)"
./gradlew bootRun
```

Alternatively, set `PLAID_TOKEN_ENCRYPTION_KEYSET` to the JSON on a **single line** in
`server/.env` (without shell quotes). Other local configuration remains in `.env`.
Tests use disposable or public test-only keysets and need no production secrets.

### Storage and rotation

The initial Flyway migrations create `plaid_items.access_token_encrypted` as TEXT.
The database stores Base64-encoded Tink ciphertext. Plaintext, corrupted data and tokens encrypted with unknown keys
are rejected on decryption.

This replaces the earlier, undeployed raw-key implementation. Its key and `v1:`
ciphertext are not compatible with this implementation; no fallback is included
because there are no existing tokens to migrate.

Keep the same keyset across deployments. For future rotation, use Tinkey to add a
new primary key while retaining the old enabled keys for decryption. Tink selects
the right key automatically. Do not replace the whole keyset or remove old keys
until all data and required backups encrypted with them have been accounted for.

Run backend tests from `server/` with `./gradlew test`.
