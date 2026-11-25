# Transport Ledger — Design Package

A small multi-tenant service that answers one question per household: **how much are we saving by giving up our leased cars?** It stores rideshare rides, car rentals (with rebates, refunds, reimbursements, and holds), and recurring costs, and computes a monthly old-vs-new comparison from a start date through any "as of" date.

Stack: SQLite (one file, all tenants) + a JDK 25 HTTP/JSON API using the JDK's built-in `HttpServer`, `sqlite-jdbc`, and Jackson. Authentication is the same tenant/key design as the grocery service.

## Contents

| File | What it is |
| --- | --- |
| [README.md](README.md) | This index. |
| [DATA-MODEL.md](DATA-MODEL.md) | Tables, DDL, scoping rules, the comparison algorithm and its SQL, indexes, migrations, worked example. |
| [API-ENDPOINTS.md](API-ENDPOINTS.md) | Every endpoint with JSON, status codes, validation and error messages; JDK 25 architecture; acceptance checklist with expected numbers. |
| [MULTI-USER-AUTH.md](MULTI-USER-AUTH.md) | Shared auth design (tenants, hashed keys, the door, operator rules). Authoritative for anything auth-related. |
| [sql/V1__schema.sql](sql/V1__schema.sql) | Schema version 1, ready to ship as `db/migration/V1__schema.sql`. |
| [sql/comparison.sql](sql/comparison.sql) | The monthly comparison query, ready to ship as a classpath resource. |
| [seed/](seed/README.md) | Real data for tenant 1 (`seed.sql`), a synthetic test fixture, a validation script, and the comparison against the original spreadsheet. |

## Reading order for the implementer

1. **MULTI-USER-AUTH.md**: the auth model the rest assumes.
2. **DATA-MODEL.md** §1–5: conventions, DDL, and tenant scoping rules.
3. **DATA-MODEL.md** §6: the comparison rules, rounding, and query.
4. **API-ENDPOINTS.md** §1–3: conventions, request pipeline, errors.
5. **API-ENDPOINTS.md** §4–11: endpoints and validation.
6. **API-ENDPOINTS.md** §12: package layout, types, `ScopedValue` context, door, stores.
7. **API-ENDPOINTS.md** §13: acceptance tests. Build them alongside the code; the numbers in §13.7 and §13.8 are verified.
8. **seed/README.md**: how to load the real data and what to expect.

Suggested build order: migrations → door + bootstrap + admin (pass §13.1–13.4) → settings and recurring costs → rides → rentals → comparison (pass §13.7–13.8) → isolation suite (§13.5).

## Scope

In scope:

- One SQLite file, many tenants, isolated by API key (operator plus ordinary tenants).
- Personal rides (tag-checked), rentals with adjustments, recurring costs (weekly or monthly, date-ranged), per-tenant settings.
- Natural-key upserts (PUT), partial updates (PATCH), deletes of domain rows, paged lists.
- The monthly comparison with exact integer arithmetic and round-half-even per month.
- Schema migrations via `PRAGMA user_version`; health check.

Out of scope (for now):

- Reading email. Clients (people or agents) parse receipts and call the API.
- Currency conversion. Rows not in the tenant's currency are stored and reported as excluded.
- TLS, rate limiting, and request logging (reverse proxy).
- A UI or charts (clients render the comparison JSON).
- Tenant deletion (disable only), multi-user roles within a tenant, key expiry.
- Spreading a rental's cost across its days; per-ride mileage or vehicle details beyond free-text notes.

## Key decisions (and why)

| Decision | Reason |
| --- | --- |
| Integer cents, never floating point | The comparison adds hundreds of prorated daily amounts; floats drift. |
| Round once per month per side, half-even; cumulative = sum of rounded months | Deterministic, matches the spreadsheet month by month, and the table always adds up to the totals. |
| `ride_date` is the receipt's local date; comparison never uses `pickup_utc` | Avoids evening rides landing in the next UTC day. Many receipts have no times at all. |
| Whole rental net attributed to `start_date` (decided) | Simple and matches the existing spreadsheet. |
| Rides are personal-only, enforced by the subject tag (decided) | One rule the ingesting client cannot get around; no `purpose` column to misuse. |
| `region` is `local`/`travel`, not a place name | Tenant-neutral; each tenant's `homeRegion` explains what local means. |
| Reimbursements and holds are recorded but `affects_net = 0` | Keeps the paper trail visible without changing cost. |
| No views; every query starts `WHERE tenant_id = ?` | A view cannot take the tenant as a parameter, so it is too easy to misuse. |
| Tenant id only from a `ScopedValue` principal | No store method can be handed a spoofed tenant id. |
| `HttpServer` + virtual threads instead of a framework | Few routes, zero extra dependencies, blocking JDBC is fine on virtual threads. |
| Jackson, read as `JsonNode` | PATCH needs missing vs `null` vs wrong type; numbers read as `BigDecimal`. |

## Decisions

Settled by the owner; implement as written.

1. **Base path matches the grocery API.** No version segment: `GET /health` at the root (open), resources under `/api/...`, tenant administration under `/api/admin/...`, own-key rotation at `PATCH /api/admin/tenant/key`, first-tenant bootstrap at `POST /api/bootstrap`.
2. **Rental cost goes entirely on the rental's start date.** The whole net (gross minus rebates and refunds) counts in the month of `start_date`, even when the rental crosses into the next month.
3. **Rides must carry the personal tag.** `emailSubject` is required on every ride write and must contain the tenant's personal tag (default `[Personal]`, case-insensitive). Anything else is rejected with `400 {"error":"emailSubject must contain the personal tag"}`. No bypass: a `purpose` field is ignored. Every stored ride is personal, so the `ride` table has no `purpose` column. Rentals keep `purpose` (default `personal`) and have no subject requirement.
4. **No one-time bootstrap token.** `POST /api/bootstrap` is open until the first tenant exists, then returns `404`. Bootstrap before the port is reachable by anyone else.

## Open questions (current default in effect)

1. **Cumulative rounding.** Default: cumulative = sum of the rounded monthly values, so the table always adds up. Through 2026-11-25 that gives $3,922.15; the spreadsheet says $3,922.16 because it rounds whole-window EV charging once. Alternative: round cumulative values from exact fractions (matches the sheet; months then do not add up exactly).
2. **Non-cash rebates.** Default: the $50 airline travel credit is a `rebate` (`affects_net = 1`) and reduces the rental's net, as the sheet does. Alternative: record it with `affects_net = 0` and count it only when spent.
3. **Placeholder message ids.** Default: seeded rides keep `sheet:ride:<hash>` ids as their permanent keys. Alternative: backfill real receipt message ids (delete the placeholder row first, or the ride exists twice).
4. **PATCH cannot clear a field.** Default: explicit `null` is ignored, matching the grocery API; use PUT to clear (for example, to make a recurring cost ongoing again).
5. **Recurring-cost currency.** Default: recurring costs are in the tenant's currency and have no currency column. Add one if a tenant ever has recurring costs in two currencies.
