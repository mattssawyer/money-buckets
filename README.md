# Money Buckets

A personal finance dashboard for automating your spending planning. 

Money Buckets is in early development. The current integration uses Plaid Sandbox.

## Features

- Sign in with Clerk.
- Connect financial accounts through Plaid Link.
- View connected account details, current balances, and available balances.
- Store user and account connections in PostgreSQL.

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

The initial migrations may change during pre-production development. If you have
already run older versions, reset the local database before restarting the backend.
From the repository root:

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

Every push to `main` publishes `ghcr.io/mattssawyer/money-buckets-server` and
`ghcr.io/mattssawyer/money-buckets-frontend`. To run them with a database:

```sh
cd docker
cp .env.example .env
docker compose up -d
```

Fill in `docker/.env` first; it lists every setting both images read. The app opens at
`http://localhost:3000`.

Pulling works without logging in only once the GHCR packages are public. Until then, log
in with a GitHub token that has the `read:packages` scope:

```sh
echo YOUR_TOKEN | docker login ghcr.io -u YOUR_GITHUB_USERNAME --password-stdin
```

To build the images from your checkout instead of pulling them:

```sh
docker compose -f compose.yaml -f compose.build.yaml up -d --build
```

The API listens only on `127.0.0.1:8080`. To run the app anywhere but your own machine,
put the frontend and the API behind a reverse proxy that serves HTTPS, and set
`FRONTEND_URL` and `API_URL` in `docker/.env` to its `https://` URLs.

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
