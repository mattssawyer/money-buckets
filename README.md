# Money Buckets

A personal finance dashboard for automating your spending planning. It pulls your bank
data from Plaid and helps you build and follow a Conscious Spending Plan: fixed costs,
investments, savings and guilt-free spending.

Money Buckets is in early development. It works with Plaid Sandbox's test banks or, with
Plaid production access, your real accounts.

## Features

- **Home:** balance, recent transactions, recurring bills and paychecks, and spending this
  month (or another period) by bucket and category, with how much guilt-free spending is
  left.
- **Spending plan:** set up the plan from your take-home pay, recurring bills and what you
  usually spend, with a buffer on fixed costs and targets for each bucket.
- **Sorting:** Jev, through [TypeSafe](https://docs.typesafe.ai), sorts transactions into
  buckets and plan lines and spots recurring payments Plaid hasn't. You can correct any
  payee by clicking a transaction.
- **Accounts:** choose which accounts count toward spending and net worth, and mark a shared
  account so only your share counts.
- **Investments:** link investment accounts and see holdings and net worth over time.

## Tech stack

- **Frontend:** Vue 3, TypeScript, Vite, Tailwind CSS, PrimeVue 5
- **Backend:** Java 17, Spring Boot, Spring Security, Spring Data JPA
- **Database:** PostgreSQL 16 with Flyway migrations
- **Integrations:** Clerk for authentication and Plaid for financial account data

## Getting started

### Prerequisites

- Node.js 22.18+ within v22, or 24.12+; npm
- Java 17
- Docker with Docker Compose
- Clerk and Plaid Sandbox credentials

### 1. Start the database

From the repository root:

```sh
docker compose up -d
```

### 2. Configure and start the backend

```sh
cd server
cp .env.example .env
```

Fill in the Clerk and Plaid values in `.env`. Configure the token encryption keyset
using the [encryption setup guide](docs/plaid-token-encryption.md). Add a
[TypeSafe](https://docs.typesafe.ai) API key as `TYPESAFE_API_KEY` to sort transactions
into spending plan buckets on the home page; without one, they show as not sorted yet.
The database defaults match the local Docker Compose configuration.

```sh
./gradlew bootRun
```

The API runs at `http://localhost:8080/api`. Flyway creates the schema on a fresh database.

Schema changes go in new Flyway migrations; don't edit ones that have already run. To
start over with an empty local database, from the repository root:

```sh
docker compose down -v
docker compose up -d
```

This deletes all local database data. Plain `docker compose down` keeps the named
volume and its tables.

### 3. Configure and start the frontend

In a separate terminal, create `frontend/.env` with:

```dotenv
VITE_CLERK_PUBLISHABLE_KEY=your_clerk_publishable_key
VITE_API_BASE_URL=http://localhost:8080/api
```

Use the same Clerk application configured for the backend, then run:

```sh
cd frontend
npm ci
npm run dev
```

Open `http://localhost:5173` to sign in and connect a sandbox account.

## Self-hosting with Docker

The `docker/` folder runs the whole app from published images: PostgreSQL, the API and
the web app. Every push to `main` publishes
`ghcr.io/mattssawyer/money-buckets-server` and `ghcr.io/mattssawyer/money-buckets-frontend`
for amd64 and arm64, and they can be pulled without logging in.

You need Docker with Docker Compose and two accounts: Clerk for sign-in and Plaid for bank
data. Both are free to start with. A TypeSafe key is optional.

### 1. Create the settings file

```sh
cd docker
cp .env.example .env
```

`docker/.env` holds every setting and secret the app reads. Keep it out of Git and back
it up somewhere safe, such as a password manager.

### 2. Fill in the keys

| Setting | Required | Where it comes from |
| --- | --- | --- |
| `POSTGRES_PASSWORD` | Yes | Any strong password you make up. The database is created with it on first start, so don't change it afterwards. |
| `CLERK_PUBLISHABLE_KEY` | Yes | [Clerk dashboard](https://dashboard.clerk.com) → your application → **API keys**. Starts with `pk_`. |
| `CLERK_FRONTEND_API_URL` | Yes | Same page, the **Frontend API URL**, such as `https://your-app.clerk.accounts.dev`. The API uses it to check sign-ins, so it must come from the same Clerk application as the publishable key. |
| `PLAID_CLIENT_ID` | Yes | [Plaid dashboard](https://dashboard.plaid.com) → **Developers** → **Keys**. |
| `PLAID_ENV` | Yes | Starts as `sandbox`, for Plaid's test banks, or `production` for real accounts once Plaid has approved production access for you. |
| `PLAID_SECRET` | Yes | Same page, the secret for the environment in `PLAID_ENV`. |
| `TYPESAFE_API_KEY` | No | [TypeSafe](https://docs.typesafe.ai). Without it the app runs, but transactions show as not sorted yet and nothing is put on plan lines or suggested as recurring. |
| `PRIMEUI_LICENSE_KEY` | No | A PrimeUI license key, if you have one. |
| `PLAID_TOKEN_ENCRYPTION_KEYSET` | No | Encrypts Plaid's access tokens in the database. Left empty, the server generates one on first start. To manage your own, see [the encryption guide](docs/plaid-token-encryption.md). |
| `COMPOSE_PROFILES`, `NGROK_AUTHTOKEN`, `NGROK_DOMAIN` | No | For faster syncing, below. |
| `FRONTEND_URL`, `API_URL` | No | Only for serving the API from a different address than the app; see the comments in `.env.example`. |

### 3. Start it

```sh
docker compose up -d
```

Open `http://localhost:3000` on the machine running it, or port 3000 at that machine's
address from another device on your network. The first start creates the database
schema and the token keyset.

### Back up your data

The database lives in `docker/data/postgres`. The generated keyset lives in the
`money-buckets_server-data` Docker volume, and the database's Plaid tokens can't be
decrypted without it, so keep a copy somewhere safe, such as a password manager:

```sh
docker compose cp server:/app/data/plaid-keyset.json ./plaid-docker-keyset.json
```

To move the app to another machine, copy the `docker` folder, then paste the keyset's JSON
into `PLAID_TOKEN_ENCRYPTION_KEYSET` in `.env` on one line, in single quotes, before
starting it there.

### Keep it up to date

To update to the latest images:

```sh
docker compose pull
docker compose up -d
```

To build the images from your checkout instead of pulling them:

```sh
docker compose -f compose.yaml -f compose.build.yaml up -d --build
```

### Faster syncing (optional)

The server syncs every linked bank on startup and every 6 hours. Plaid can also tell the
server about new transactions as they arrive, through webhooks, but that needs a public
HTTPS address. The Compose file can run an [ngrok](https://ngrok.com) tunnel for it with a
free ngrok account: in `.env`, uncomment `COMPOSE_PROFILES=webhooks`, and set
`NGROK_AUTHTOKEN` (ngrok dashboard → **Your Authtoken**) and `NGROK_DOMAIN` (ngrok
dashboard → **Domains**, your free static domain without `https://`).

Plaid saves the webhook address on each account when it's linked, so set this up before
linking accounts and keep the same domain afterwards.

### Reaching it from outside your network

The web app passes `/api` through to the API, so browsers never reach the API directly;
the API's own port listens only on `127.0.0.1:8080`. To use the app away from home, put
port 3000 behind a reverse proxy that serves HTTPS. A Clerk production instance only
works on the domain it's set up for, so give it that address.

## Development commands

| Directory | Command | Purpose |
| --- | --- | --- |
| `server/` | `./gradlew test` | Run backend tests |
| `frontend/` | `npm run build` | Type-check and build the frontend |
| `frontend/` | `npm run lint` | Lint and apply fixes |

## Project structure

```text
frontend/     Vue application
server/       Spring Boot API and database migrations
docs/         Additional setup documentation
docker/       Dockerfiles and the self-hosting Compose file
compose.yaml  Local PostgreSQL service
```
