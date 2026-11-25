# Transport Ledger — API Endpoints

HTTP/JSON API over the store in [DATA-MODEL.md](DATA-MODEL.md). Authentication, tenants, and keys follow [MULTI-USER-AUTH.md](MULTI-USER-AUTH.md), the same design the grocery service uses. If this document and MULTI-USER-AUTH.md ever disagree on an auth detail, **MULTI-USER-AUTH.md wins**; flag the difference instead of guessing.

Sections: 1 Conventions · 2 Auth and request pipeline · 3 Errors · 4 Health and bootstrap · 5 Rides · 6 Rentals · 7 Recurring costs · 8 Settings · 9 Comparison · 10 Tenant administration and keys · 11 Validation reference · 12 JDK 25 implementation · 13 Acceptance checklist

## 1. Conventions

| Topic | Rule |
| --- | --- |
| Base path | Same layout as the grocery API: `GET /health` at the root (open), every resource under `/api/...`, tenant administration under `/api/admin/...`, own-key rotation at `PATCH /api/admin/tenant/key`. There is no version segment. `GET /health` and `POST /api/bootstrap` are the only routes outside the door. (The grocery bot guide documents no bootstrap path; `/api/bootstrap` is this service's choice.) |
| Transport | Plain HTTP on `TRANSPORT_BIND:TRANSPORT_PORT` (default `127.0.0.1:8080`). TLS and rate limiting (~10 req/s per client, `429`) are the reverse proxy's job and out of scope. |
| Content type | Requests with a body send `Content-Type: application/json` (charset UTF-8). Responses with a body are `application/json; charset=utf-8`. Max request body 1 MiB. |
| Field names | camelCase in JSON (`rideDate`), snake_case in SQL (`ride_date`). |
| Money | JSON **number**, ≥ 0, at most 2 decimal places, at most `1000000.00`. Parsed as `BigDecimal` (never `double`), stored as cents. Responses always write exactly 2 decimals (`66.45`, `0.00`). A string such as `"66.45"` is a `400`. |
| Dates | `YYYY-MM-DD`, strict (`2026-02-30` is a `400`). Instants: ISO-8601 UTC ending in `Z`, e.g. `2026-11-24T16:24:00Z`. |
| Enums | Lowercase strings exactly as listed (`personal`, `local`, `weekly`...). Any other value, including different case, is a `400`. |
| Strings | Trimmed of leading and trailing whitespace before validation and storage. A string that is empty after trimming is treated as absent. Control characters (U+0000–U+001F and U+007F) are rejected with `400`, except tab and newline inside `notes`. |
| Nulls on output | Every documented field is always present in responses; absent values are `null`. |
| Unknown fields | Ignored on input (no error). Read-only fields (`total` on rentals, `net`, `affectsNet`, `createdAt`, `updatedAt`, key fields that come from the path) are ignored if sent. |
| PUT | Create or **fully replace** the resource at a natural key. `201` when created, `200` when replaced. Fields omitted on a replace take their defaults or `null`. Idempotent: resending the same body is safe. |
| PATCH | Partial update. Present scalar fields replace stored values; explicit `null` and unknown fields are **ignored** (to change a field, send its new value). An array field (`adjustments`), when present, must be an array and **replaces** the whole set. The merged result is re-validated as if it were a PUT. `404` if the resource does not exist (PATCH never creates). |
| DELETE | `204` with no body. `404` if missing. |
| Lists | Paged: `page` (0-based, default 0), `size` (default 50, 1..200). Response `{"content":[...],"page":0,"size":50,"total":132}`. `total` is the full match count. A page past the end returns `content: []`. |

### 1.1 Path parameters

Natural keys appear in the path. Each key is **one path segment**, percent-encoded by the client (RFC 3986; at minimum encode `/`, `%`, `?`, `#`, space, and non-ASCII).

The server must:

1. Read the raw path (`HttpExchange.getRequestURI().getRawPath()`), never the decoded path.
2. Split on `/`.
3. Percent-decode each segment as UTF-8. `+` stays a literal `+` (do **not** use `URLDecoder`, which turns `+` into a space).
4. Malformed escapes (`%G1`, truncated `%4`) or invalid UTF-8 → `400 {"error":"invalid path encoding"}`.

Example: the message id `<CAF+x9@mail.example.com>` is sent as `/api/rides/%3CCAF%2Bx9%40mail.example.com%3E`.

## 2. Auth and request pipeline

Every route under `/api/` except `POST /api/bootstrap` requires:

```text
Authorization: Bearer <secret>
```

### 2.1 The door (in MULTI-USER-AUTH order)

1. Header missing, not `Bearer`, or empty/blank secret → reject.
2. `SHA-256(UTF-8 secret)` as lowercase hex; look up `tenant_key.key_hash`. Not found → reject.
3. Key `is_enabled = 0` → reject.
4. Load the key's tenant. Missing, `is_enabled = 0`, or id mismatch → reject.
5. Allowed: bind `Principal(tenantId, keyId, operator)` for the rest of the request. There is no fallback tenant, no default tenant, no "anonymous" mode.

Every rejection is the same response, whatever the reason:

```http
HTTP/1.1 401
Content-Type: application/json; charset=utf-8

{"error":"unauthorized - missing or invalid API key"}
```

The scheme word `Bearer` is matched case-insensitively; exactly one space-separated token must follow. The response never reveals which step failed. Do not log the header.

### 2.2 Processing order

For every protected request, checks run in this order and the first failure wins:

1. Door → `401`.
2. Route match → `404 {"error":"not found"}` (unknown path) or `405 {"error":"method not allowed"}` (known path, wrong method; include an `Allow` header).
3. Operator check on operator routes → `403 {"error":"forbidden"}`.
4. Body parse: not JSON, wrong top-level type, or over 1 MiB → `400` / `413`.
5. Path and field validation → `400`.
6. Existence and ownership → `404 {"error":"not found"}`.
7. State conflicts → `409`.

Unauthenticated callers therefore get `401` for every `/api/...` path, including unknown ones.

## 3. Errors

All error responses are JSON `{"error":"<message>"}`.

| Status | When | Body |
| --- | --- | --- |
| 400 | Invalid JSON, wrong type, failed validation, bad path encoding | `{"error":"<field> <problem>"}` (see §11) |
| 401 | Any door rejection | `{"error":"unauthorized - missing or invalid API key"}` |
| 403 | Non-operator on an operator route | `{"error":"forbidden"}` |
| 404 | Unknown route, missing resource, **or another tenant's resource** | `{"error":"not found"}` |
| 405 | Wrong method on a known route | `{"error":"method not allowed"}` |
| 409 | State conflict (see each endpoint) | `{"error":"<reason>"}` |
| 413 | Body over 1 MiB | `{"error":"request body too large"}` |
| 500 | Unexpected failure | `{"error":"internal error"}` (no stack traces, SQL, or ids) |

Cross-tenant rule: a request for a natural key that exists only under another tenant takes exactly the same code path and returns exactly the same `404` as a key that exists nowhere. List endpoints simply never include other tenants' rows.

## 4. Health and bootstrap

### 4.1 `GET /health` (no auth)

```http
200 {"status":"ok"}
```

Returns `200` once migrations have finished (the port does not open before that). It does not touch tenant data.

### 4.2 `POST /api/bootstrap` (no auth, one time)

Decided: no one-time bootstrap token. The route is open until the first tenant exists; deploy so the operator bootstraps before the port is reachable by anyone else.

Creates the first tenant as the operator, with its first key. Works only while the `tenant` table is empty.

Request:

```json
{"name": "Ron", "keyLabel": "desktop"}
```

| Field | Rule |
| --- | --- |
| `name` | required, 1–80 chars |
| `keyLabel` | optional, 1–40 chars, default `"default"` |

Response `201`:

```json
{"tenantId": 1, "name": "Ron", "isOperator": true, "keyId": 1, "keyLabel": "desktop",
 "apiKey": "q3Vb...64 chars...Zk"}
```

- The server generates the secret: 48 bytes from `SecureRandom`, base64url without padding (64 chars). It is returned **once** and never again.
- The check "table is empty" and the inserts run in one `BEGIN IMMEDIATE` transaction, so two racing bootstrap calls cannot both succeed.
- If any tenant exists: `404 {"error":"not found"}` (the route behaves as if it does not exist).

## 5. Rides

Resource shape (response):

```json
{
  "emailMessageId": "<CAF+x9@mail.example.com>",
  "provider": "Uber",
  "region": "local",
  "rideDate": "2026-11-24",
  "pickupUtc": "2026-11-24T16:24:00Z",
  "dropoffUtc": "2026-11-24T16:53:00Z",
  "pickupAddr": "Bowie, MD",
  "dropoffAddr": "Laurel, MD",
  "currency": "USD",
  "fare": 50.95,
  "tip": 15.50,
  "total": 66.45,
  "paymentLast4": "3346",
  "rider": null,
  "emailSubject": "[Personal] Your Tuesday morning trip with Uber",
  "emailReceivedUtc": null,
  "notes": "Electric; 20.45 miles, 28 minutes",
  "createdAt": "2026-11-25T17:02:11.418Z",
  "updatedAt": "2026-11-25T17:02:11.418Z"
}
```

### 5.1 `PUT /api/rides/{emailMessageId}` — create or replace

Request body: every field of the resource except `emailMessageId` (from the path), `fare`, `createdAt`, `updatedAt`.

| Field | Required | Rule |
| --- | --- | --- |
| `provider` | yes | 1–40 chars (e.g. `Uber`, `Lyft`) |
| `rideDate` | yes | date; the trip date printed on the receipt |
| `region` | yes | `local` or `travel` |
| `total` | yes | money; amount charged including tip, after credits |
| `tip` | no | money, default `0.00`, must be ≤ `total` |
| `emailSubject` | yes | 1–300 chars; must contain the tenant's personal tag (see below) |
| `currency` | no | 3 uppercase letters; default = tenant settings currency, or `USD` if no settings |
| `pickupUtc`, `dropoffUtc` | no | instant; if both present, `dropoffUtc ≥ pickupUtc` |
| `pickupAddr`, `dropoffAddr` | no | ≤ 200 chars |
| `paymentLast4` | no | exactly 4 digits |
| `rider` | no | ≤ 80 chars |
| `emailReceivedUtc` | no | instant |
| `notes` | no | ≤ 2000 chars |

Path: `emailMessageId` 1–512 chars after decoding.

**Personal tag rule (decided).** The ledger stores personal rides only; there is no `purpose` field on rides. `emailSubject` must contain the tenant's `personalTag` (case-insensitive substring; `[Personal]` when the tenant has no settings yet). Otherwise:

```http
400 {"error":"emailSubject must contain the personal tag"}
```

There is no bypass. A `purpose` field in the body is ignored like any unknown field, so `"purpose":"personal"` (or `"business"`) with an untagged subject is still rejected with the message above. A missing `emailSubject` is `400 {"error":"emailSubject is required"}`.

Stored `fare_cents = total − tip`.

Responses: `201` (created) or `200` (replaced) with the resource. Example:

```bash
curl -s -X PUT "$BASE/api/rides/%3CCAF%2Bx9%40mail.example.com%3E" \
  -H "Authorization: Bearer $TRANSPORT_API_KEY" -H 'Content-Type: application/json' \
  -d '{"provider":"Uber","rideDate":"2026-11-24","region":"local","total":66.45,"tip":15.50,
       "emailSubject":"[Personal] Your Tuesday morning trip with Uber"}'
# -> 201, fare 50.95 (subject carries [Personal], so the ride is accepted)
```

### 5.2 `PATCH /api/rides/{emailMessageId}`

PATCH semantics from §1. If `total` or `tip` changes, `fare` is recomputed; `tip > total` after the merge is a `400`. If the patch contains `emailSubject`, the personal tag rule runs on the new value (same `400`). A patch without `emailSubject` keeps the stored subject and does not re-check it, even if the tenant's tag has changed since. `200` with the resource, or `404`.

### 5.3 `GET /api/rides/{emailMessageId}`

`200` with the resource, or `404`.

### 5.4 `DELETE /api/rides/{emailMessageId}`

`204`, or `404`.

### 5.5 `GET /api/rides` — list

| Param | Rule |
| --- | --- |
| `region` | optional enum |
| `currency` | optional 3 letters |
| `provider` | optional, exact match, case-insensitive |
| `from`, `to` | optional dates, inclusive, on `rideDate`; `from > to` is `400` |
| `page`, `size` | §1 |

Sorted by `rideDate` ascending, then creation order. `200` with a page object of ride resources. Unknown query parameters are ignored; invalid values are `400` (e.g. `{"error":"region must be one of: local, travel"}`).

## 6. Rentals

Resource shape (response):

```json
{
  "company": "Budget",
  "confirmationNo": "06832678US6",
  "bookingChannel": "MileagePlus Travel",
  "startDate": "2026-11-06",
  "endDate": "2026-11-09",
  "location": "BWI Airport, MD",
  "purpose": "personal",
  "region": "local",
  "currency": "USD",
  "gross": 468.93,
  "net": 418.93,
  "paymentLast4": null,
  "emailMessageId": null,
  "emailSubject": "Your E-receipt From Budget",
  "notes": null,
  "adjustments": [
    {"lineNo": 1, "kind": "rebate", "amount": 50.00, "affectsNet": true,
     "effectiveDate": "2026-11-11", "holdReleased": null, "emailMessageId": null,
     "notes": "Airline travel credit earned by this rental"}
  ],
  "createdAt": "2026-11-25T17:02:11.418Z",
  "updatedAt": "2026-11-25T17:02:11.418Z"
}
```

`net` = `gross − Σ amount of adjustments with affectsNet = true`. `affectsNet` is derived from `kind` (`rebate`, `refund` → `true`; `reimbursement`, `hold` → `false`). Adjustments are always returned sorted by `lineNo`.

### 6.1 `PUT /api/rentals/{company}/{confirmationNo}` — create or replace

Path: `company` 1–40 chars (case-insensitive key; first-written spelling kept), `confirmationNo` 1–64 chars.

| Field | Required | Rule |
| --- | --- | --- |
| `startDate` | yes | date (pickup) |
| `endDate` | yes | date (return), `≥ startDate` |
| `region` | yes | `local` or `travel` |
| `gross` | yes | money; final charged amount, excluding holds/deposits |
| `purpose` | no | default `personal` |
| `currency` | no | default as for rides |
| `bookingChannel` | no | ≤ 60 chars |
| `location` | no | ≤ 200 chars |
| `paymentLast4` | no | 4 digits |
| `emailMessageId` | no | ≤ 512 chars |
| `emailSubject` | no | ≤ 300 chars |
| `notes` | no | ≤ 2000 chars |
| `adjustments` | no | array, default `[]`; replaces the whole set |

Each adjustment:

| Field | Required | Rule |
| --- | --- | --- |
| `lineNo` | yes | integer ≥ 1, unique within the array |
| `kind` | yes | `rebate`, `refund`, `reimbursement`, `hold` |
| `amount` | yes | money, > 0, in the rental's currency |
| `effectiveDate` | yes | date |
| `holdReleased` | for `hold` only | boolean, required when `kind = hold`; must be absent or `null` otherwise |
| `emailMessageId` | no | ≤ 512 chars |
| `notes` | no | ≤ 2000 chars |

Extra rule: Σ net-affecting adjustments ≤ `gross`, otherwise `400 {"error":"adjustments exceed gross"}`.

The rental row and its adjustments are written in one transaction: upsert the rental, delete its existing adjustments, insert the new set. `201` or `200` with the resource.

Rental cost attribution for the comparison: the full net counts on `startDate` (see DATA-MODEL.md §4.4).

### 6.2 `PATCH /api/rentals/{company}/{confirmationNo}`

PATCH semantics from §1; `adjustments`, if present, replaces the set. `200` or `404`.

### 6.3 `GET /api/rentals/{company}/{confirmationNo}` / `DELETE` (same path)

`GET` → `200` or `404`. `DELETE` → `204` (adjustments go with it) or `404`.

### 6.4 `GET /api/rentals` — list

Params: `purpose`, `region`, `currency`, `company` (case-insensitive exact), `from`/`to` (inclusive, on `startDate`), `page`, `size`. Sorted by `startDate`, then creation order. Rentals include their adjustments.

## 7. Recurring costs

Resource shape:

```json
{"name": "Lease A", "effectiveFrom": "2026-07-26", "effectiveTo": null,
 "side": "baseline", "period": "monthly", "amount": 645.00, "notes": null,
 "createdAt": "...", "updatedAt": "..."}
```

### 7.1 `PUT /api/recurring-costs/{name}/{effectiveFrom}`

Path: `name` 1–60 chars (case-sensitive), `effectiveFrom` date.

| Field | Required | Rule |
| --- | --- | --- |
| `side` | yes | `baseline` or `current` |
| `period` | yes | `weekly` or `monthly` |
| `amount` | yes | money, ≥ 0, in the tenant's currency |
| `effectiveTo` | no | date ≥ `effectiveFrom`; `null`/absent = ongoing |
| `notes` | no | ≤ 2000 chars |

`409 {"error":"recurring cost overlaps an existing range for this name"}` if another row of this tenant with the same `name` (and a different `effectiveFrom`) has an overlapping inclusive range. `201` or `200`.

To change a price: `PATCH` the old row's `effectiveTo` to the last day of the old price, then `PUT` a new row starting the next day.

### 7.2 `PATCH /api/recurring-costs/{name}/{effectiveFrom}`

Mutable: `effectiveTo`, `amount`, `side`, `period`, `notes`. The overlap check re-runs. `200` or `404`. (Since explicit `null` is ignored, an end date cannot be cleared by PATCH; use PUT to make a row ongoing again.)

### 7.3 `GET` / `DELETE` `/api/recurring-costs/{name}/{effectiveFrom}`

`200` / `204`, or `404`.

### 7.4 `GET /api/recurring-costs`

Params: `side` (optional), `activeOn` (optional date: only rows with `effectiveFrom ≤ activeOn ≤ effectiveTo` or open-ended). Not paged (small); returns a JSON **array** sorted by `name`, then `effectiveFrom`.

## 8. Settings

### 8.1 `GET /api/settings`

`200`:

```json
{"comparisonStart": "2026-07-26", "currency": "USD", "homeRegion": "DC/MD/VA",
 "personalTag": "[Personal]", "timeZone": "America/New_York",
 "createdAt": "...", "updatedAt": "..."}
```

`404 {"error":"not found"}` if this tenant has no settings yet.

### 8.2 `PUT /api/settings`

| Field | Required | Rule |
| --- | --- | --- |
| `comparisonStart` | yes | date |
| `homeRegion` | yes | 1–80 chars |
| `currency` | no | default `USD` |
| `personalTag` | no | 1–40 chars, default `[Personal]` |
| `timeZone` | no | valid `ZoneId`, default `UTC` |

`201` first time, `200` after. Changing settings never rewrites rides, rentals, or costs.

## 9. Comparison

### 9.1 `GET /api/comparison?asOf=YYYY-MM-DD`

`asOf` optional; default = today's date in the tenant's `timeZone`. Algorithm and SQL: DATA-MODEL.md §6.

`200`:

```json
{
  "currency": "USD",
  "comparisonStart": "2026-07-26",
  "asOf": "2026-11-25",
  "months": [
    {"month": "2026-07", "windowFrom": "2026-07-26", "windowTo": "2026-07-31", "days": 6,
     "oldCost": 301.73, "rides": 73.73, "rideCount": 1, "rentals": 0.00, "rentalCount": 0,
     "recurringNew": 6.86, "newCost": 80.59, "saved": 221.14,
     "excludedRides": 0, "excludedRentals": 0,
     "cumulativeOldCost": 301.73, "cumulativeNewCost": 80.59, "cumulativeSaved": 221.14}
  ],
  "totals": {"days": 123, "oldCost": 6266.59, "newCost": 2344.44, "saved": 3922.15,
             "rideCount": 14, "rentalCount": 3, "excludedRides": 12, "excludedRentals": 0}
}
```

- `months` is ordered oldest first. `totals` are sums of the rounded monthly values, so `totals.saved` always equals the last month's `cumulativeSaved`.
- `saved` and the cumulative values may be negative.
- `asOf < comparisonStart` → `200` with `months: []` and every total `0` / `0.00`.
- No settings → `409 {"error":"settings not configured"}`.
- Invalid `asOf` → `400 {"error":"asOf must be a date (YYYY-MM-DD)"}`.

## 10. Tenant administration and keys

Operator routes return `403 {"error":"forbidden"}` to any non-operator principal, before any validation or lookup. Responses never include `key_hash` or any secret except the one-time `apiKey` on creation. Generated secrets are 48 `SecureRandom` bytes, base64url, no padding.

### 10.1 `POST /api/admin/tenants` (operator)

Request `{"name":"Household 2","keyLabel":"desktop"}` (`name` 1–80 required; `keyLabel` 1–40 optional, default `default`).
`201 {"tenantId":2,"name":"Household 2","isOperator":false,"keyId":3,"keyLabel":"desktop","apiKey":"<shown once>"}`.

### 10.2 `GET /api/admin/tenants` (operator)

`200`, array sorted by `tenantId`:

```json
[{"tenantId": 1, "name": "Ron", "isOperator": true, "isEnabled": true,
  "createdAt": "...", "updatedAt": "...",
  "keys": [{"keyId": 1, "label": "desktop", "isEnabled": true, "createdAt": "...", "updatedAt": "..."}]}]
```

### 10.3 `PATCH /api/admin/tenants/{tenantId}` (operator)

Body: `name` (1–80) and/or `isEnabled` (boolean). `200` with the tenant object (as in 10.2). `404` unknown id. `409 {"error":"cannot disable your own tenant"}` when the operator sets its own tenant `isEnabled:false`. There is no tenant DELETE route (`405`).

### 10.4 `POST /api/admin/tenants/{tenantId}/keys` (operator)

Issue an additional key (for example a separate key for an automation agent). Body `{"label":"agent"}` (1–40, required). `201 {"keyId":4,"tenantId":2,"label":"agent","apiKey":"<shown once>"}`. `404` unknown tenant.

### 10.5 `PATCH /api/admin/keys/{keyId}` (operator)

Body `{"isEnabled": false}` (required boolean). Rules for disabling:

- the key presenting this request → `409 {"error":"cannot disable the key in use"}`
- the operator tenant's last enabled key → `409 {"error":"cannot disable the operator's last key"}`

Re-enabling (`true`) is always allowed. `200 {"keyId":4,"tenantId":2,"label":"agent","isEnabled":false}`. `404` unknown key.

### 10.6 `PATCH /api/admin/tenant/key` (any authenticated tenant) — rotate own key

Body `{"apiKey":"<new secret>"}`. Replaces the hash of **the presenting key only** (same `keyId`, same label); other keys of the tenant are untouched. The old secret stops working immediately.

| Check | Result |
| --- | --- |
| `apiKey` missing, not a string, < 32 chars, > 512 chars, or containing whitespace/control chars | `400 {"error":"apiKey must be 32-512 printable characters with no whitespace"}` |
| SHA-256 of the new secret equals any existing key's hash (including the presenting key) | `409 {"error":"apiKey already in use"}` |
| OK | `200 {"keyId": N}` — the secret is **not** echoed |

## 11. Validation reference

Error messages follow `"<field> <problem>"` with these exact forms (tests may match them literally):

| Problem | Message |
| --- | --- |
| Body not valid JSON / not an object | `request body must be a JSON object` |
| Required field missing | `<field> is required` |
| Wrong JSON type | `<field> must be a <string|number|boolean|array|object>` |
| Bad date | `<field> must be a date (YYYY-MM-DD)` |
| Bad instant | `<field> must be an ISO-8601 UTC instant (…Z)` |
| Bad enum | `<field> must be one of: a, b` |
| Money: negative, > 2 decimals, or > 1000000.00 | `<field> must be a non-negative amount with at most 2 decimals` |
| Adjustment amount = 0 | `adjustments[<i>].amount must be greater than 0` |
| Length | `<field> must be <min>-<max> characters` |
| Currency | `<field> must be a 3-letter uppercase currency code` |
| Last 4 | `paymentLast4 must be 4 digits` |
| Date order | `endDate must not be before startDate` / `effectiveTo must not be before effectiveFrom` / `dropoffUtc must not be before pickupUtc` / `from must not be after to` |
| Tip | `tip must not exceed total` |
| Ride subject without the personal tag | `emailSubject must contain the personal tag` |
| Duplicate line | `adjustments[<i>].lineNo is duplicated` |
| Hold flag | `adjustments[<i>].holdReleased is required for holds` / `adjustments[<i>].holdReleased is only allowed for holds` |
| Over-adjusted | `adjustments exceed gross` |
| Paging | `page must be >= 0` / `size must be 1-200` |
| Path | `invalid path encoding` / `<param> must be <min>-<max> characters` |
| Time zone | `timeZone must be a valid IANA time zone` |

Fields inside arrays are reported as `adjustments[<index>].<field>` with a 0-based index. Validation stops at the first error.

## 12. JDK 25 implementation

### 12.1 Stack

| Piece | Choice | Why |
| --- | --- | --- |
| Language | Java 25 (`--release 25`), no preview features | `ScopedValue` (final in 25), records, sealed types, pattern-matching `switch`, unnamed variables `_`. |
| HTTP | JDK `com.sun.net.httpserver.HttpServer` with `Executors.newVirtualThreadPerTaskExecutor()` | Zero dependencies, a handful of routes, virtual threads make blocking JDBC fine. A framework would add more surface than it removes. |
| Database | `org.xerial:sqlite-jdbc` (3.54.x or newer) | The standard SQLite JDBC driver; bundles native SQLite. |
| JSON | `com.fasterxml.jackson.core:jackson-databind` 2.x (2.23 or newer) | Mature, record-aware, reads numbers as `BigDecimal` (`USE_BIG_DECIMAL_FOR_FLOATS`) so money never touches `double`. Requests are read as `JsonNode` trees so the code can tell missing vs `null` vs wrong type (PATCH needs this). |
| Tests | JUnit Jupiter 6.x + `java.net.http.HttpClient` | End-to-end tests against a real server on an ephemeral port and a temp database file. |
| Build | Maven, single module, `maven-shade-plugin` (or `jar` + `dependency:copy`) for a runnable jar | Simple, reproducible. |

If `sqlite-jdbc` pulls in `slf4j-api`, add `slf4j-jdk14` (or `slf4j-nop`) so logging goes through `java.util.logging`. The app logs via `System.Logger`.

Configuration by environment variable: `TRANSPORT_DB_PATH` (default `./transport.db`), `TRANSPORT_BIND` (default `127.0.0.1`), `TRANSPORT_PORT` (default `8080`).

### 12.2 Package layout

```text
org.miosys.transport
├── App.java                      // main: config → Database → Migrations → HttpServer.start()
├── config/Config.java            // record read from env
├── db/
│   ├── Database.java             // connection factory: pragmas on every connection
│   ├── Migrations.java           // PRAGMA user_version runner (DATA-MODEL.md §8)
│   └── Tx.java                   // inTransaction(conn -> ...) helper using BEGIN IMMEDIATE for writes
├── auth/
│   ├── Principal.java  RequestContext.java  DoorResult.java  Door.java
│   ├── Bearer.java  Sha256.java  Secrets.java       // header parse, hashing, SecureRandom secrets
│   └── KeyRepository.java  TenantRepository.java   // the only cross-tenant SQL
├── http/
│   ├── ApiHandler.java           // single HttpHandler: door → route → errors
│   ├── Router.java  Route.java   // method + segment patterns → handler lambdas
│   ├── PathCodec.java            // raw-path split + strict percent-decoding
│   ├── Json.java                 // ObjectMapper config, read tree, send(status, body)
│   ├── Validator.java            // field rules from §11, produces BadRequest
│   └── ApiException.java         // sealed: BadRequest, NotFound, Forbidden, Conflict, PayloadTooLarge, MethodNotAllowed
├── domain/
│   ├── Money.java  Enums.java  Ride.java  Rental.java  Adjustment.java
│   ├── RecurringCost.java  TenantSettings.java  Page.java
│   └── MonthlyComparison.java  ComparisonReport.java
├── store/
│   ├── TransportStore.java  SqliteTransportStore.java
│   ├── TenantAdmin.java  SqliteTenantAdmin.java
│   ├── KeySelfService.java  SqliteKeySelfService.java
│   └── ComparisonQuery.java      // loads sql/comparison.sql from the classpath
└── resources/
    ├── db/migration/V1__schema.sql
    └── sql/comparison.sql
```

### 12.3 Domain types

```java
public record Money(long cents, String currency) {
    public Money { if (currency == null || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException(); }
    public static Money of(BigDecimal amount, String currency) {               // validated: scale <= 2, >= 0
        return new Money(amount.movePointRight(2).longValueExact(), currency);
    }
    public BigDecimal toDecimal() { return BigDecimal.valueOf(cents, 2); }     // always 2 decimals on output
}

public enum Purpose { PERSONAL, BUSINESS }          // rentals only; JSON/SQL: lowercase name()
public enum Region  { LOCAL, TRAVEL }
public enum Side    { BASELINE, CURRENT }
public enum Period  { WEEKLY, MONTHLY }

public record Ride(String emailMessageId, String provider, Region region,   // no purpose: rides are personal
                   LocalDate rideDate, Optional<Instant> pickupUtc, Optional<Instant> dropoffUtc,
                   Optional<String> pickupAddr, Optional<String> dropoffAddr,
                   Money fare, Money tip, Optional<String> paymentLast4, Optional<String> rider,
                   String emailSubject, Optional<Instant> emailReceivedUtc,  // required, carries the tag
                   Optional<String> notes) {
    public Money total() { return new Money(fare.cents() + tip.cents(), fare.currency()); }
}

public sealed interface Adjustment permits Rebate, Refund, Reimbursement, Hold {
    int lineNo(); long amountCents(); LocalDate effectiveDate(); Optional<String> notes();
    boolean affectsNet();
}
public record Rebate(int lineNo, long amountCents, LocalDate effectiveDate, Optional<String> notes)
        implements Adjustment { public boolean affectsNet() { return true; } }
public record Refund(int lineNo, long amountCents, LocalDate effectiveDate, Optional<String> notes)
        implements Adjustment { public boolean affectsNet() { return true; } }
public record Reimbursement(int lineNo, long amountCents, LocalDate effectiveDate, Optional<String> notes)
        implements Adjustment { public boolean affectsNet() { return false; } }
public record Hold(int lineNo, long amountCents, LocalDate effectiveDate, boolean released, Optional<String> notes)
        implements Adjustment { public boolean affectsNet() { return false; } }
// (emailMessageId omitted above for brevity; include it as Optional<String> on each record)

public record Rental(String company, String confirmationNo, Optional<String> bookingChannel,
                     LocalDate startDate, LocalDate endDate, Optional<String> location,
                     Purpose purpose, Region region, Money gross, List<Adjustment> adjustments
                     /* + paymentLast4, emailMessageId, emailSubject, notes as Optional<String> */) {
    public Money net() {
        long off = adjustments.stream().filter(Adjustment::affectsNet).mapToLong(Adjustment::amountCents).sum();
        return new Money(gross.cents() - off, gross.currency());
    }
}

public record MonthlyComparison(YearMonth month, LocalDate windowFrom, LocalDate windowTo, int days,
        long oldCents, long rideCents, int rideCount, long rentalCents, int rentalCount,
        long recurringNewCents, int excludedRides, int excludedRentals) {
    public long newCents()   { return rideCents + rentalCents + recurringNewCents; }
    public long savedCents() { return oldCents - newCents(); }
}
```

Mapping adjustments from rows uses an exhaustive `switch` on `kind`; rendering uses pattern matching:

```java
String describe(Adjustment a) {
    return switch (a) {
        case Rebate r        -> "Rebate " + r.amountCents();
        case Refund r        -> "Refund " + r.amountCents();
        case Reimbursement r -> "Reimbursed (net zero) " + r.amountCents();
        case Hold h          -> h.released() ? "Hold released" : "Hold open " + h.amountCents();
    };
}
```

### 12.4 Request context, door, and filter

```java
public record Principal(long tenantId, long keyId, boolean operator) {}

public final class RequestContext {
    public static final ScopedValue<Principal> PRINCIPAL = ScopedValue.newInstance();
    public static Principal current() {
        if (!PRINCIPAL.isBound()) throw new IllegalStateException("no principal bound");
        return PRINCIPAL.get();
    }
    private RequestContext() {}
}

public sealed interface DoorResult permits DoorResult.Allowed, DoorResult.Rejected {
    record Allowed(Principal principal) implements DoorResult {}
    enum Rejected implements DoorResult { INSTANCE }          // one reason, never revealed
}

public final class Door {
    private final KeyRepository keys; private final TenantRepository tenants;
    public DoorResult open(String authorizationHeader) {
        var bearer = Bearer.parse(authorizationHeader);                       // 1
        if (bearer.isEmpty()) return DoorResult.Rejected.INSTANCE;
        var key = keys.findByHash(Sha256.hex(bearer.get()));                  // 2
        if (key.isEmpty() || !key.get().enabled()) return DoorResult.Rejected.INSTANCE;   // 3
        var tenant = tenants.find(key.get().tenantId());                      // 4
        if (tenant.isEmpty() || !tenant.get().enabled() || tenant.get().id() != key.get().tenantId())
            return DoorResult.Rejected.INSTANCE;
        return new DoorResult.Allowed(new Principal(tenant.get().id(), key.get().id(), tenant.get().operator()));  // 5
    }
}

// ApiHandler.handle — the only place a Principal is ever created or bound
public void handle(HttpExchange ex) throws IOException {
    try (ex) {
        var path = PathCodec.segments(ex.getRequestURI().getRawPath());
        if (isOpen(ex.getRequestMethod(), path)) { router.route(ex, path); return; }   // /health, bootstrap
        switch (door.open(ex.getRequestHeaders().getFirst("Authorization"))) {
            case DoorResult.Allowed(var p) ->
                ScopedValue.where(RequestContext.PRINCIPAL, p).call(() -> { router.route(ex, path); return null; });
            case DoorResult.Rejected _ ->
                Json.send(ex, 401, Map.of("error", "unauthorized - missing or invalid API key"));
        }
    } catch (ApiException e) { /* map to status + {"error":...} per §3 */ }
      catch (Exception e)    { /* log without secrets; 500 {"error":"internal error"} */ }
}
```

Rules: no store method takes a tenant id parameter; stores call `RequestContext.current().tenantId()`. Calling a store outside a bound scope throws (and is a test failure). Operator routes call `requireOperator()` first, which throws `Forbidden` unless `RequestContext.current().operator()`.

### 12.5 Store interfaces

```java
/** Everything is scoped to RequestContext.current().tenantId(). */
public interface TransportStore {
    Upserted<Ride>   putRide(Ride ride);                          // created? + stored row
    Optional<Ride>   patchRide(String emailMessageId, JsonNode patch);
    Optional<Ride>   ride(String emailMessageId);
    boolean          deleteRide(String emailMessageId);
    Page<Ride>       rides(RideQuery q, int page, int size);

    Upserted<Rental> putRental(Rental rental);                    // replaces adjustments in the same tx
    Optional<Rental> patchRental(String company, String confirmationNo, JsonNode patch);
    Optional<Rental> rental(String company, String confirmationNo);
    boolean          deleteRental(String company, String confirmationNo);
    Page<Rental>     rentals(RentalQuery q, int page, int size);

    Upserted<RecurringCost> putRecurringCost(RecurringCost c);    // Conflict on overlap
    Optional<RecurringCost> patchRecurringCost(String name, LocalDate from, JsonNode patch);
    Optional<RecurringCost> recurringCost(String name, LocalDate from);
    boolean                 deleteRecurringCost(String name, LocalDate from);
    List<RecurringCost>     recurringCosts(Optional<Side> side, Optional<LocalDate> activeOn);

    Optional<TenantSettings> settings();
    Upserted<TenantSettings> putSettings(TenantSettings s);

    ComparisonReport comparison(LocalDate asOf);                  // Conflict if no settings
}
public record Upserted<T>(boolean created, T value) {}

/** Operator-only. */
public interface TenantAdmin {
    IssuedTenant bootstrap(String name, String keyLabel);        // open route; NotFound unless tenant table empty
    IssuedTenant createTenant(String name, String keyLabel);
    List<TenantView> tenants();
    TenantView updateTenant(long tenantId, Optional<String> name, Optional<Boolean> enabled);
    IssuedKey issueKey(long tenantId, String label);
    KeyView setKeyEnabled(long keyId, boolean enabled);
}
/** Any principal, own presenting key only. */
public interface KeySelfService { long rotate(String newSecret); }

public record IssuedTenant(long tenantId, String name, boolean operator, long keyId, String keyLabel, String apiKey) {}
public record IssuedKey(long keyId, long tenantId, String label, String apiKey) {}
```

Upsert implementation (gives 201 vs 200 without races): inside `BEGIN IMMEDIATE`, `SELECT ride_pk FROM ride WHERE tenant_id = ? AND email_message_id = ?`; if absent `INSERT`, else `UPDATE ... WHERE tenant_id = ? AND ride_pk = ?` (set `updated_at`); then re-read and return.

Connections: open one connection per request from `Database` (cheap for SQLite), run the pragmas, close at the end. Writes use `BEGIN IMMEDIATE` so concurrent writers wait on `busy_timeout` instead of failing mid-transaction.

### 12.6 Startup

1. Read `Config`. 2. `Migrations.run()` (DATA-MODEL.md §8); exit non-zero on any failure. 3. Create `HttpServer` on `TRANSPORT_BIND:TRANSPORT_PORT` with the virtual-thread executor and a single context `/` → `ApiHandler`. 4. `start()`. 5. Log `listening on host:port` and the schema version (never keys or hashes).

## 13. Acceptance checklist

Each item is an automated test against a real server and a fresh temp database unless noted. "T1" = operator tenant from bootstrap, "T2" = tenant created by T1.

### 13.1 Bootstrap and health

- [ ] `GET /health` → `200 {"status":"ok"}` without a header, and before any tenant exists.
- [ ] `POST /api/bootstrap` on an empty DB → `201`, `isOperator: true`, `apiKey` is 64 base64url chars; the key works on `GET /api/settings` (→ `404`, no settings yet).
- [ ] Second bootstrap → `404 {"error":"not found"}`. Two concurrent bootstraps on an empty DB → exactly one `201`.
- [ ] The database contains the SHA-256 hex of the secret, never the secret (search the DB file bytes for the secret: no match).

### 13.2 Door (uniform 401)

For each, expect `401` with body exactly `{"error":"unauthorized - missing or invalid API key"}`:

- [ ] No `Authorization` header. · [ ] `Authorization: Basic abc`. · [ ] `Bearer` with empty/blank token.
- [ ] Unknown secret. · [ ] Disabled key of an enabled tenant. · [ ] Enabled key of a disabled tenant.
- [ ] Unknown path under `/api/` without a key (401, not 404).
- [ ] Response bodies and timing do not differ by failure reason in a way a test can observe (bodies byte-identical).

### 13.3 Operator boundary

- [ ] T2 calling every `/api/admin/tenants*` and `/api/admin/keys/*` route → `403 {"error":"forbidden"}`, even with an invalid body (403 before 400).
- [ ] T1 creates T2 → `201` with a one-time `apiKey`; `GET /api/admin/tenants` never shows secrets or hashes.
- [ ] T1 disabling its own tenant → `409 {"error":"cannot disable your own tenant"}`.
- [ ] T1 disabling the key it is presenting → `409 {"error":"cannot disable the key in use"}`.
- [ ] T1 has keys A and B. Presenting A: disable B → `200`; disable A → `409 {"error":"cannot disable the key in use"}`; re-enable B → `200`. Presenting B: disable A → `200`.
- [ ] Last-key guard (defense in depth; unreachable over HTTP while the in-use rule holds, because the presenting key is always an enabled operator key): store-level unit test binds `Principal(tenantId=1, keyId=<some other id>, operator=true)` and disables T1's only enabled key → `Conflict("cannot disable the operator's last key")`.
- [ ] Disabling T2 → T2's keys get `401` on the next request; re-enabling restores access.
- [ ] `DELETE /api/admin/tenants/2` → `405`.

### 13.4 Rotation

- [ ] T2 rotates with a 32+ char secret → `200 {"keyId":N}` (no secret in body); old secret → `401`; new secret → `200`; T2's other keys still work.
- [ ] 31-char secret → `400`. Secret equal to any existing key's secret (including the current one) → `409 {"error":"apiKey already in use"}`.

### 13.5 Tenant isolation

- [ ] T1 `PUT` ride `m-1`; T2 `GET`, `PATCH`, `DELETE` `/api/rides/m-1` → `404 {"error":"not found"}` each, and T1's row is unchanged.
- [ ] T2 `PUT` ride `m-1` → `201` (separate row); both tenants see only their own in `GET /api/rides`.
- [ ] Same for rentals (`Avis/R-1`) and recurring costs (`Lease/2026-07-26`). T2 cannot add or replace adjustments on T1's rental.
- [ ] T2's comparison is unaffected by T1's data (load the seed into T1 and the synthetic fixture into T2; T2 results equal §13.8 exactly).
- [ ] Code review item: every SQL statement in `store/` contains `tenant_id = ?` (or reaches `rental_adjustment` through `rental.tenant_id = ?`); grep-based test enforced in CI.

### 13.6 Resources and validation

- [ ] Ride `PUT` twice with the same body → `201` then `200`; one row. Fare = total − tip in the response.
- [ ] Ride with `emailSubject` containing `[personal]` (any case) → `201`. Subject without the tag → `400 {"error":"emailSubject must contain the personal tag"}`. Same subject plus `"purpose":"personal"` → same `400`; plus `"purpose":"business"` → same `400` (no bypass). Missing `emailSubject` → `400 {"error":"emailSubject is required"}`. Responses never contain a `purpose` field for rides.
- [ ] Tenant with `personalTag` `[Home]`: subject `[Home] trip` → `201`; subject `[Personal] trip` → `400`. PATCH that sets an untagged `emailSubject` → `400`; PATCH of `notes` only → `200`.
- [ ] Message id with `/`, `+`, `@`, `<>` round-trips via percent-encoding; `+` is not turned into a space; `%ZZ` → `400 {"error":"invalid path encoding"}`.
- [ ] `rideDate: "2026-02-30"` → `400 {"error":"rideDate must be a date (YYYY-MM-DD)"}`. `total: "12.00"` → `400 {"error":"total must be a number"}`. `total: 12.345` → `400`. `tip > total` → `400 {"error":"tip must not exceed total"}`. `region: "Local"` → `400`.
- [ ] Rental PUT with adjustments replaces the set; PATCH with `"adjustments": []` clears them; `net` updates accordingly.
- [ ] Hold without `holdReleased` → `400`; rebate with `holdReleased` → `400`; duplicate `lineNo` → `400`; rebates + refunds > gross → `400 {"error":"adjustments exceed gross"}`. `affectsNet` sent by the client is ignored.
- [ ] `Avis` and `avis` are the same rental key.
- [ ] Recurring cost overlapping the same name → `409`; adjacent ranges (old `effectiveTo` = D, new `effectiveFrom` = D+1) → `201`.
- [ ] PATCH with `"notes": null` leaves notes unchanged; unknown fields ignored.
- [ ] Lists: `size=201` → `400`; paging walks all rows; `from > to` → `400`; `total` correct.
- [ ] Body > 1 MiB → `413`. Non-JSON body → `400 {"error":"request body must be a JSON object"}`.

### 13.7 Comparison — real configuration (tenant 1 seed)

Load `seed/seed.sql` into T1 (see seed/README.md). `GET /api/comparison?asOf=2026-11-25` must return exactly:

| month | window | days | oldCost | rides (#) | rentals (#) | recurringNew | newCost | saved | cumulativeSaved | excl. rides |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-07 | 07-26..07-31 | 6 | 301.73 | 73.73 (1) | 0.00 (0) | 6.86 | 80.59 | 221.14 | 221.14 | 0 |
| 2026-08 | 08-01..08-31 | 31 | 1558.92 | 225.55 (3) | 0.00 (0) | 35.43 | 260.98 | 1297.94 | 1519.08 | 6 |
| 2026-09 | 09-01..09-30 | 30 | 1552.92 | 175.00 (4) | 957.54 (2) | 34.29 | 1166.83 | 386.09 | 1905.17 | 1 |
| 2026-10 | 10-01..10-31 | 31 | 1558.92 | 110.45 (2) | 0.00 (0) | 35.43 | 145.88 | 1413.04 | 3318.21 | 3 |
| 2026-11 | 11-01..11-25 | 25 | 1294.10 | 242.66 (4) | 418.93 (1) | 28.57 | 690.16 | 603.94 | 3922.15 | 2 |

Totals: `days 123, oldCost 6266.59, newCost 2344.44, saved 3922.15, rideCount 14, rentalCount 3, excludedRides 12, excludedRentals 0`.

- [ ] The July baseline is `301.73` = round-half-even(6,547,464 / 217 cents) (DATA-MODEL.md §6.4).
- [ ] The two 2026-07-18 rides (before `comparisonStart`) are neither counted nor excluded.
- [ ] `asOf=2026-07-25` → `months: []`, totals all zero.
- [ ] Without settings → `409 {"error":"settings not configured"}`.

### 13.8 Comparison — synthetic fixture (every rule, small numbers)

`seed/fixture-synthetic.sql` defines tenants 2 and 3 directly in SQL for store-level tests (for API tests, create the same rows through the endpoints). This is invented test data, not real spending.

Tenant 2, `comparisonStart 2026-09-15`, `asOf=2026-10-10`:

| month | window | days | oldCost | rides (#) | rentals (#) | recurringNew | newCost | saved | excl. rides |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-09 | 09-15..09-30 | 16 | 1680.00 | 12.00 (1) | 80.00 (1) | 11.00 | 103.00 | 1577.00 | 1 |
| 2026-10 | 10-01..10-10 | 10 | 992.74 | 18.50 (1) | 0.00 (0) | 10.00 | 28.50 | 964.24 | 1 |

Totals: `oldCost 2672.74, newCost 131.50, saved 2541.24`. What it proves:

- [ ] Monthly proration over a partial first month: $3,000 × 16/30 = $1,600.00; fuel $35/week × 16/7 = $80.00.
- [ ] `effectiveTo` is inclusive and stops a cost mid-month: fuel counts Oct 1–5 only (5/7 × $35 = $25.00); October baseline = 10/31 × $3,000 + $25.00 = 992.7419… → `992.74`.
- [ ] A current cost starting after `comparisonStart` (EV from 09-20) counts 11 days in September.
- [ ] Ride before `comparisonStart`, ride after `asOf`: ignored. Travel ride and CAD ride: excluded and counted in `excludedRides` (Sep 1, Oct 1).
- [ ] Rental 09-29..10-02 counts fully in September; net = 100.00 − 20.00 rebate = 80.00; the reimbursement (5.00) and hold (50.00) do not change it.

Tenant 3 (half-even tie), `comparisonStart 2026-09-30`, `asOf=2026-09-30`: baseline $0.75/month for 1 day of a 30-day month = 2.5 cents → **0.02**; current $0.45/month = 1.5 cents → **0.02**; saved **0.00**.

- [ ] Half-even rounding (half-up would give 0.03 baseline).
