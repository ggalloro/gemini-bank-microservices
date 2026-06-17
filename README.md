# Gemini Bank — Microservices

A small **sample** online bank, decomposed into four services that communicate
over HTTP/JSON. Three are Python/Flask; the **ledger** is Java / Spring Boot 3.

> All data is synthetic. No real credentials, accounts, or anything sensitive.

## Architecture

```
                ┌──────────────┐
   browser ───▶ │   frontend   │  :8080  (UI only, no DB)
                └──────┬───────┘
            ┌──────────┼───────────────┐
            ▼          ▼               ▼
     ┌────────────┐ ┌────────────┐ ┌──────────────┐
     │   users    │ │   ledger   │ │  statements  │
     │   :8081    │ │   :8082    │ │    :8083     │
     │  users.db  │ │ ledger.db  │ │   (no DB)    │
     └────────────┘ └─────┬──────┘ └──────┬───────┘
                          └───── REST ────┘
                       (statements reads from ledger)
```

- **frontend** (`:8080`) — server-rendered Jinja2 + Bootstrap 5 (via CDN). No
  database, no business logic; it orchestrates calls to the backends and stores
  the login token in the session cookie.
- **users** (`:8081`) — Python/Flask. Owns identity in `users.db`. Mints a JWT
  (HS256, `PyJWT`) on login.
- **ledger** (`:8082`) — **Java / Spring Boot 3** (Maven, `JdbcTemplate` +
  SQLite). Owns money in `ledger.db`: accounts, deposits, payments (internal +
  external), and a raw internal transaction feed.
- **statements** (`:8083`) — Python/Flask. Read-only reporting. No database of
  its own; it calls the ledger's internal API and aggregates in memory.

**Database-per-service:** no service ever opens another service's database file.
Cross-service reads go over HTTP.

**Auth:** the users service mints a JWT (HS256) signed with the shared
`TOKEN_SECRET` carrying `user_id` and `full_name`; the frontend forwards it as
`Authorization: Bearer <token>`; the Java ledger validates the signature with
the same secret.

## Repository layout

```
gemini-bank-microservices/
├── docker-compose.yml
├── seed.py
├── frontend/     # Python/Flask — UI only, no DB
├── users/        # Python/Flask — identity; mints JWTs
├── ledger/       # Java / Spring Boot 3 — money
└── statements/   # Python/Flask — read-only reporting
```

## Key business rules (ledger)

- A payment is rejected when the amount exceeds the account balance.
- Internal payments are atomic: the source is debited and the destination
  credited together, or neither happens.
- Amounts must be positive with at most two decimals; anything else is rejected.
- Money is held as integer cents internally and shown as 2-decimal amounts.

## Run with Docker Compose

Because the ledger is compiled Java (built in a multi-stage Dockerfile), Docker
Compose is the way to run the full stack:

```bash
docker compose up --build -d
# wait for the healthchecks to go green, then:
python seed.py            # populates synthetic users + ~60 days of data
```

Open <http://localhost:8080> and log in (see credentials below). Everything is
up and usable within ~30 seconds. Tear down with `docker compose down -v`.

`seed.py` defaults to `localhost` on the published ports, so running it on the
host works as-is.

### Running a second stack (per-service ports)

Published host ports are env vars with defaults (`FRONTEND_PORT` 8080,
`USERS_PORT` 8081, `LEDGER_PORT` 8082, `STATEMENTS_PORT` 8083); container ports
never change. To run a **second** stack side by side, apply an offset and a
distinct project name:

```bash
COMPOSE_PROJECT_NAME=gemini-bank-2 \
  FRONTEND_PORT=9080 USERS_PORT=9081 LEDGER_PORT=9082 STATEMENTS_PORT=9083 \
  docker compose up --build -d
# reachable at http://localhost:9080
```

See [`.env.example`](.env.example) for the full set of variables.

## Seed credentials

After running `seed.py`:

| Username | Full name      | Password   |
|----------|----------------|------------|
| `ana`    | Ana Ferreira   | `demo1234` |
| `bruno`  | Bruno Costa    | `demo1234` |
| `carla`  | Carla Nunes    | `demo1234` |
| `david`  | David Klein    | `demo1234` |
| `elena`  | Elena Rossi    | `demo1234` |

## Tests

The **users** service ships a pytest suite and the **ledger** service ships a
JUnit/Spring `MockMvc` suite (happy path, validation errors, insufficient funds,
atomic internal payment).

```bash
( cd users  && pip install -r requirements.txt && python -m pytest -q )
( cd ledger && mvn test )
```

## Environment variables

| Variable          | Used by              | Default                  |
|-------------------|----------------------|--------------------------|
| `TOKEN_SECRET`    | users, ledger        | `dev-secret-change-me`   |
| `USERS_URL`       | frontend, seed       | `http://localhost:8081`  |
| `LEDGER_URL`      | frontend, statements, seed | `http://localhost:8082` |
| `STATEMENTS_URL`  | frontend             | `http://localhost:8083`  |
| `USERS_DB`        | users                | `users.db`               |
| `LEDGER_DB`       | ledger               | `ledger.db`              |
| `FRONTEND_SECRET` | frontend             | `dev-frontend-secret`    |

## Safety hook

The repo ships an Antigravity `PreToolUse` safety gate at `.agents/hooks.json`
+ `.agents/hooks/block-destructive-ops.sh`. It denies irreversible shell
commands (`git push --force`, `git reset --hard`, `rm -rf /`, `chmod 777`,
`curl | bash`, …) for every agent conversation in this project.
