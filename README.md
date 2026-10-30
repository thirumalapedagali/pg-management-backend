# pg-management-backend

Multi-tenant REST API for the **MyPG** app (rooms, residents, rent collection, expenses, dashboards).
Spring Boot 4 / Java 17 / PostgreSQL. Auth, tenancy and user/PG management are ported from `stock-management`
(`shopId` became `pgId`, "shop" became "PG").

## Run

```powershell
# Local, in-memory H2, throw-away secrets (superadmin / DevAdmin#123)
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"

# Tests (end-to-end flow through the real HTTP stack)
./mvnw test
```

Production: set the variables in [`.env.example`](.env.example) in the environment. `JWT_SECRET` (>= 32 chars) is
mandatory; the app refuses to start without it. On an empty database set `DDL_AUTO=update` once (or add Flyway).
Set `BOOTSTRAP_ADMIN_PASSWORD` to create the first SUPER_ADMIN, then change its password.

## Model

`SUPER_ADMIN` (platform) -> many **PGs** -> each has `OWNER` / `MANAGER` / `RESIDENT` users and its own rooms,
residents, payments, expenses, categories. Every tenant table has `pg_id`; it is filled from the JWT-derived
`TenantContext`, never from the request body, and every query filters on it.

Billing rule (same as the app): a resident owes the room rent for each calendar month from the join month through
the current month; `due = max(0, billed - paid)`. "Today" uses `APP_TIMEZONE` (default `Asia/Kolkata`).

## API

Errors: `{"code": "...", "message": "..."}`. Money is a JSON number; dates are `YYYY-MM-DD`.

| Area | Endpoints | Roles |
|---|---|---|
| Auth | `POST /api/auth/login` (username or mobile), `POST /api/auth/refresh`, `POST /api/auth/select-pg`, `POST /api/auth/logout`, `PUT /api/auth/change-password` | login/refresh public |
| PGs ("shops") | `GET/POST /api/admin/pgs`, `PUT /api/admin/pgs/{id}/status?active=`, `PUT /api/admin/pgs/{id}/license?months=` | SUPER_ADMIN |
| Users | `GET/POST /api/users`, `GET/PUT/DELETE /api/users/{id}` | SUPER_ADMIN, OWNER |
| Rooms | `GET /api/rooms` (residents see availability only), `POST`, `PUT /{id}`, `DELETE /{id}` | GET: all; write: staff |
| Residents | `GET/POST /api/residents`, `GET/PUT/DELETE /api/residents/{id}` | staff |
| Payments | `GET /api/payments?residentId&from&to`, `POST`, `DELETE /{id}` | staff |
| Expenses | `GET /api/expenses?from&to&category&q&page&size`, `GET /summary`, `POST`, `PUT /{id}`, `DELETE /{id}` | staff |
| Categories | `GET/POST /api/master/expenses`, `DELETE /{id}` | staff |
| Dashboard | `GET /api/dashboard/summary` (Today/Month/Year/Total, occupancy, 6-month series, pending dues), `GET /api/dashboard/today` | staff |
| Resident | `GET /api/me/dues` | RESIDENT |

Login response: `{token, role, userId, username, pgId, pgName, residentId, requiresPgSelection, pgs, mustChangePassword}`.
A SUPER_ADMIN gets `requiresPgSelection: true` and a platform token; call `/select-pg` for a PG-scoped one.

## Security notes

- Secrets only from environment. Nothing sensitive is committed.
- The JWT filter re-reads the user and PG on every request, so blocking a user or deactivating/expiring a PG
  takes effect immediately, not at token expiry.
- Invalid tokens yield 401 (the original code could throw 500); a stale token never breaks `/login`.
- Login errors are identical for unknown user / bad password / blocked user.

## What was intentionally not ported from stock-management

Products, orders, harvest, partners, investments, inventory, S3/Cloudinary, WhatsApp/SMS/e-mail notifications.
Add them back only if the PG product needs them.

## Rotate these now

`stock-management/src/main/resources/application.properties` contains plaintext AWS keys, the Neon DB password, a
WhatsApp token, a Gmail app password and a Cloudinary URL, and `JwtUtil` hardcodes the JWT secret. They are in git
history; rotate them and move them to environment variables.
