# Information security policy

Money Buckets (moneybuckets.io) is a free, invite-only budgeting app run by one person,
Matthew Sawyer. This policy covers the hosted service and the people and systems that run
it. It is reviewed at least once a year, and whenever the systems it describes change.

Owner: Matthew Sawyer, matthewsawyer67@gmail.com. Last reviewed: 2026-10-09.

## Scope and data

The service stores consumer financial data received from Plaid: account names, types,
masks and balances, transactions, recurring payments and investment balances. It also
stores what users enter (spending plans, corrections, exclusions) and a daily balance
snapshot per account. It never receives bank credentials or full account and routing
numbers. The [privacy policy](https://moneybuckets.io/privacy) lists what is collected
and which processors handle it.

Systems in scope:

| System | Purpose |
|---|---|
| Railway | Hosts the backend, frontend and Postgres database |
| WorkOS | End-user sign-in |
| Plaid | Bank connections |
| TypeSafe | Sorts transactions; gets no names, emails or account numbers |
| GitHub | Source code and CI |
| Vercel | DNS for moneybuckets.io |

## Access control

- Only Matthew Sawyer has access to production systems, the database and the admin
  dashboards above. No one else is granted access without updating this policy.
- Every admin account above uses multi-factor authentication.
- Sign-up is invite-only. Users sign in through WorkOS AuthKit; the app never handles
  passwords.
- Every user must use multi-factor authentication: WorkOS requires an authenticator-app code
  at sign-in.
- Each user's data is scoped to their own user ID, and automated tests check that every
  endpoint keeps users' data apart.
- API keys and dashboard access that are no longer needed are revoked.

### Roles

Access is granted by role. Nobody holds a role that isn't listed here.

| Role | Who | Can access | Granted by |
|---|---|---|---|
| User | Invited people | Only their own data, through the app's API | A WorkOS invitation from the operator |
| Operator | Matthew Sawyer | Production systems, the database and every admin dashboard in scope | Being the owner; anyone else needs this policy updated first |

The app has no admin role and no admin endpoints: the operator can't read a user's data
through the app, only through the database.

### Access reviews

Every quarter, a scheduled GitHub Actions workflow opens an access review issue. The
operator checks who has access to each system in scope, revokes keys, tokens and members
that are no longer needed, skims the providers' admin audit logs, checks for overdue
vulnerability alerts and reviews the end-of-life table below, then closes the issue as the
record of the review.

### Offboarding

The organization has no personnel besides the owner. If anyone is given access, it is
granted per system and recorded in this policy. When they leave or change role, the same
day: their access to every system in scope is removed or reduced, any secret they could
see is rotated, and the next access review confirms nothing was missed.

## Authentication and zero trust

No request is trusted because of where it comes from on the network; each one proves who
sent it.

- Every API request except Plaid's webhook needs a short-lived WorkOS access token (a JWT).
  The API checks its signature against WorkOS's published keys, its issuer and expiry, and
  that it was issued to this app's client ID, so tokens issued to other WorkOS apps are
  refused.
- Refresh tokens are single-use: each refresh returns a new one.
- Plaid webhooks are accepted only with a valid Plaid-signed JWT.
- All traffic uses HTTPS with certificates from public certificate authorities; Railway
  issues and renews the app's.
- The production database has no public network address and needs credentials even on
  Railway's private network.
- Admin accounts sign in with passkeys or another second factor; there is no VPN or
  network location that grants access on its own.

## Encryption

- **In transit:** all traffic between browsers, the app, Plaid, WorkOS and TypeSafe uses
  HTTPS (TLS 1.2 or later).
- **At rest:** the database sits on Railway's encrypted storage. Plaid access tokens are
  additionally encrypted in the application with AES-256-GCM (Google Tink) before they
  are written to the database. See [plaid-token-encryption.md](plaid-token-encryption.md).

## Secrets management

- Secrets (Plaid secret, token-encryption keyset, WorkOS API key, TypeSafe API key) are
  kept only in Railway's sealed variables and in the owner's password manager.
- Secrets are never committed to Git or put in frontend variables. The public repository is scanned by GitHub secret scanning.
- A secret that may have been exposed is rotated immediately.

## Secure development and vulnerability management

- Changes go through pull requests to `main`, which is the only branch that deploys.
- GitHub Actions runs the test suite, including the user-separation tests, and the
  frontend type check on every pull request and every push to `main`.
- Vulnerability scanning runs continuously on the public repository:
  - Dependabot alerts and security updates cover the frontend's npm packages and, through
    the Gradle dependency-submission workflow, the backend's dependencies and build
    classpath.
  - Dependabot version updates bump dependencies, Docker base images and GitHub Actions
    every week.
  - CodeQL scans the code on every pull request and push to `main`.
  - Secret scanning, with push protection, blocks committed secrets.
- Critical and high-severity findings are fixed within 7 days, others within 30 days.
- Production runs on Railway's managed platform, which patches the underlying hosts.
- The web app sends a strict Content-Security-Policy: only its own scripts and Plaid Link
  run, inline scripts are blocked, Trusted Types keep markup out of the page except through
  Vue, and the page can only connect to the API, WorkOS and Plaid. The browser keeps the
  WorkOS refresh token in local storage; each refresh token works once.

## End-of-life software

Software in use is upgraded before its support ends. Dependabot's weekly version updates
keep minor versions current; major versions are tracked here, checked at each access review
against [endoflife.date](https://endoflife.date), and upgraded at least 6 months before
support ends where a supported version exists.

| Software | Version in use | Support ends |
|---|---|---|
| Java (Eclipse Temurin) | 25 | 2031-09-30 |
| Spring Boot | 4.1 | 2027-07-31 |
| Node.js (builds only) | 24 | 2028-04-30 |
| PostgreSQL | 16 locally; prod version checked at each review | 16: 2028-11-09 |
| nginx (self-hosting image) | 1.30 | Stable branch, supported |
| Caddy (production web server) | 2 | Supported; Railway's build picks the version |

## Devices

The owner's computer uses full-disk encryption (FileVault), and admin credentials are kept
in a password manager. Production data is not downloaded to personal devices except briefly
to answer a user's data request, and is deleted afterwards.

## Logging and monitoring

- Railway keeps application and request logs. Logs do not include Plaid access tokens
  or other secrets.
- WorkOS, GitHub, Railway and Plaid keep their own audit logs of admin sign-ins and
  changes.

## Data retention and deletion

- Data is kept while a user has an account.
- Removing a bank disconnects it at Plaid and deletes its transactions and recurring
  payments.
- Deleting an account in Settings disconnects all of the user's banks at Plaid, deletes
  everything stored about them, and deletes their WorkOS sign-in, right away. Copies in
  logs and hosting backups expire on the providers' schedules.
- Users can email the owner to ask for a copy of their data, a correction, or deletion.
- The database is not currently backed up.

## Incident response

If a security incident is suspected:

1. **Contain:** revoke or rotate affected secrets, disable affected accounts or Plaid
   Items, and take the service offline if needed.
2. **Assess:** use logs and provider audit logs to find what was accessed and whose
   data was affected.
3. **Notify:** email affected users without undue delay, notify Plaid, and meet any
   legal notification duties.
4. **Fix and record:** fix the cause, then write down what happened and what changed.

## Vendors

Processors are chosen for their security practices (SOC 2 reports, encryption,
MFA support) and receive only the data they need. Adding a processor means updating
this policy and the privacy policy.
