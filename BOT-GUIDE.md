# Transport Ledger — Bot Guide

Transport-spend storage and old-vs-new comparison. Base URL (production):

```text
https://transportation-history.miosys.org
```

(If the operator points you at a dev deployment instead, use that base URL;
everything else below is identical.)

## 0. Handoff (the operator fills this in before giving you this file)

```text
Base URL: https://transportation-history.miosys.org
API key:  <paste the bot key here>
```

Export it at the start of every session (`export TRANSPORT_API_KEY="<key>"`),
or keep it in your secret manager. This is the only copy of the key you will
ever see — if you lose it, the operator must disable this tenant, not recover it.

## The one rule

Your API key is your identity. It selects exactly one tenant (a person or
household), and you can only ever see that tenant's data. There is no way to
list, read, or modify anyone else's rows. A `404` means "missing, or someone
else's" — treat both the same and never probe further.

## 1. Auth

Every request except `GET /health` sends:

```bash
curl -H "Authorization: Bearer $TRANSPORT_API_KEY" ...
```

- Keep the key in an environment variable or secret store, never in code, logs, output, or chat. Never print it.
- If you don't have a key, ask the operator. They create a tenant for you and show the key **once** — save it immediately, it is never shown again.
- A wrong, missing, or disabled key returns `401 {"error":"unauthorized - missing or invalid API key"}`. Check the key; do not retry blindly.

## 2. Core concepts

- **Upsert keys** are natural keys from the URL path. `PUT` creates (`201`) or fully replaces (`200`). Re-sending the same receipt never duplicates — retries are safe.
- **Rides are personal-only.** Every ride write requires `emailSubject` containing your tenant's personal tag (default `[Personal]`, any case). Anything else is `400`. There is no `purpose` on rides; a `purpose` field you send is ignored.
- **Rentals** key on `(company, confirmationNo)`; company matches case-insensitively. The whole net counts on `startDate`, even across month boundaries. Adjustments: `rebate`/`refund` reduce the net; `reimbursement`/`hold` are paper trail only.
- **Recurring costs** key on `(name, effectiveFrom)`. A price change is a new row: end-date the old row, add the next-day row. Ranges for one name must not overlap.
- **Dates** are strict `YYYY-MM-DD`. Instant fields are ISO-8601 UTC ending in `Z`.
- **Money** is JSON numbers (dollars.cents), never strings. Responses always carry exactly 2 decimals.
- **`region`** is `local` or `travel`. Only `local` counts in the comparison.
- **`lineNo`** starts at 1, is unique per rental, and orders adjustments.

## 3. Endpoints

### Health (no auth)

```bash
GET /health
# -> 200 {"status":"ok"}
```

### Settings (do this first)

```bash
PUT /api/settings
{"comparisonStart": "2026-07-26", "homeRegion": "DC/MD/VA",
 "currency": "USD", "personalTag": "[Personal]", "timeZone": "America/New_York"}
# -> 201 first time, 200 after. comparisonStart/homeRegion required, rest defaulted.
GET /api/settings
```

The comparison needs settings (`409` without them). `comparisonStart` is the first day that counts.

### Write a ride (create or replace)

```bash
PUT /api/rides/<url-encoded-message-id>
```

```json
{
  "provider": "Uber",
  "rideDate": "2026-11-24",
  "region": "local",
  "total": 66.45,
  "tip": 15.50,
  "emailSubject": "[Personal] Your Tuesday morning trip with Uber",
  "pickupAddr": "Bowie, MD",
  "dropoffAddr": "Laurel, MD",
  "notes": "Electric; 20.45 miles, 28 minutes"
}
```

Required: `provider`, `rideDate` (the date printed on the receipt), `region`,
`total`, `emailSubject` (must carry the personal tag). `tip` defaults to `0`;
`fare` is computed as `total - tip`. Optional: `currency` (defaults to your
settings currency), `pickupUtc`/`dropoffUtc`, `pickupAddr`/`dropoffAddr`,
`paymentLast4` (4 digits only, never full numbers), `rider`, `emailReceivedUtc`,
`notes`. The message id is any stable unique string (receipt `Message-ID`, or
`manual:<date>:<n>`).

`PATCH` merges scalars (`tip`/`total` recompute `fare`); explicit `null`s are
ignored. `GET` reads one, `DELETE` removes (`204`).
`GET /api/rides?region=local&currency=USD&provider=Uber&from=2026-11-01&to=2026-11-30&page=0&size=50`
lists (paged `{content, page, size, total}`, sorted by date).

### Write a rental (create or replace)

```bash
PUT /api/rentals/Budget/06832678US6
```

```json
{
  "startDate": "2026-11-06",
  "endDate": "2026-11-09",
  "region": "local",
  "gross": 468.93,
  "purpose": "personal",
  "bookingChannel": "MileagePlus Travel",
  "location": "BWI Airport, MD",
  "adjustments": [
    {"lineNo": 1, "kind": "rebate", "amount": 50.00,
     "effectiveDate": "2026-11-11", "notes": "Airline travel credit"}
  ]
}
```

Required: `startDate`, `endDate` (>= start), `region`, `gross` (final charge,
excluding holds). Each adjustment needs `lineNo`, `kind`, `amount` (> 0),
`effectiveDate`; `hold` also needs boolean `holdReleased` (forbidden on other
kinds). Rebates plus refunds cannot exceed `gross`. A PUT (or PATCH with
`adjustments`) replaces the whole adjustment set. `GET`/`DELETE` one,
`GET /api/rentals?purpose=&region=&currency=&company=&from=&to=&page=&size=`
lists with adjustments included.

### Write a recurring cost (create or replace)

```bash
PUT /api/recurring-costs/Lease%20A/2026-07-26
{"side": "baseline", "period": "monthly", "amount": 645.00}
# name + effectiveFrom from the path; effectiveTo null/absent = ongoing
```

`side` is `baseline` (old lease/fuel world) or `current` (EV charging, etc.);
`period` is `weekly` or `monthly`. Overlapping ranges for one name are `409`.
`GET /api/recurring-costs?side=&activeOn=` returns an array (not paged).

### Comparison (your data only)

```bash
GET /api/comparison?asOf=2026-11-25
# asOf optional, defaults to today in your timeZone
```

```json
{
  "currency": "USD", "comparisonStart": "2026-07-26", "asOf": "2026-11-25",
  "months": [
    {"month": "2026-11", "windowFrom": "2026-11-01", "windowTo": "2026-11-25", "days": 25,
     "oldCost": 1294.10, "rides": 242.66, "rideCount": 4, "rentals": 418.93, "rentalCount": 1,
     "recurringNew": 28.57, "newCost": 690.16, "saved": 603.94,
     "excludedRides": 2, "excludedRentals": 0,
     "cumulativeOldCost": 6266.59, "cumulativeNewCost": 2344.44, "cumulativeSaved": 3922.15}
  ],
  "totals": {"days": 123, "oldCost": 6266.59, "newCost": 2344.44, "saved": 3922.15,
             "rideCount": 14, "rentalCount": 3, "excludedRides": 12, "excludedRentals": 0}
}
```

Counts only rows in your currency dated inside the window: local rides, net of
personal local rentals on `startDate`, prorated recurring costs. `saved` may be
negative. Totals always equal the last cumulative row.

### Key rotation (self-service, careful)

```bash
PATCH /api/admin/tenant/key
{"apiKey": "<brand-new secret, 32-512 chars, no whitespace>"}
# -> 200 {"keyId": N}. The old secret dies instantly.
```

Rotates only the key you authenticated with. Generate the replacement first (48
random bytes, base64), store it, then call. If you lose the new secret you are
locked out — the operator cannot recover it. A secret matching any existing key
returns `409`.

### Owner-only (expect 403)

Tenant administration (`POST/GET/PATCH /api/admin/tenants…`, `PATCH /api/admin/keys/…`)
requires the operator tenant. If you get `403 {"error":"forbidden"}`, that endpoint is
not for you — ask the operator instead of retrying.

## 4. Errors

All errors are JSON `{"error":"..."}` except `429` (plain nginx page).

| Status | Meaning | What to do |
| --- | --- | --- |
| 200 / 201 / 204 | Success | Continue |
| 400 | Your request is invalid; body says what | Fix it, then retry once |
| 401 | Bad, missing, or disabled key | Check the key, then stop |
| 403 | Owner-only endpoint | Ask the operator |
| 404 | Missing, or another tenant's | Treat as missing; do not probe |
| 409 | State conflict (overlap, key in use, settings missing) | Read the message, fix state |
| 413 | Body over 1 MiB | Send less |
| 429 | Too fast (limit ~10 req/s) | Slow down, back off, retry |
| 500 | Server trouble | Retry with backoff, then escalate |

## 5. Rules for bots

1. Never print, log, commit, or paste the API key. Read it from the environment.
2. `PUT` is idempotent by natural key — safe to retry the exact same body after a timeout or `500`.
3. Every ride `emailSubject` must carry the personal tag; check client-side before sending.
4. Validate `YYYY-MM-DD` dates and required fields client-side before sending.
5. Page lists to completion; never assume one page is everything.
6. Stay under ~10 requests/second; on `429`, wait and slow down.
7. `404` ends the line of inquiry. No guessing keys, no scanning.
8. `PATCH` nulls do nothing — to change a field, send its new value; to clear one, `PUT` the full resource.
9. Report anomalies (unexpected `500`s, totals that don't reconcile) to the operator instead of working around them silently.

## 6. Quick start

```bash
export TRANSPORT_API_KEY="<paste-once-then-forget>"
curl -s https://transportation-history.miosys.org/health
# {"status":"ok"}
curl -s -X PUT "https://transportation-history.miosys.org/api/settings" \
  -H "Authorization: Bearer $TRANSPORT_API_KEY" -H 'Content-Type: application/json' \
  -d '{"comparisonStart":"2026-07-26","homeRegion":"DC/MD/VA"}'
curl -s -X PUT "https://transportation-history.miosys.org/api/rides/TEST-1" \
  -H "Authorization: Bearer $TRANSPORT_API_KEY" -H 'Content-Type: application/json' \
  -d '{"provider":"Uber","rideDate":"2026-11-24","region":"local","total":10.00,"emailSubject":"[Personal] Smoke test"}'
curl -s "https://transportation-history.miosys.org/api/comparison?asOf=2026-11-25" \
  -H "Authorization: Bearer $TRANSPORT_API_KEY"
curl -s -X DELETE "https://transportation-history.miosys.org/api/rides/TEST-1" \
  -H "Authorization: Bearer $TRANSPORT_API_KEY" -o /dev/null -w '%{http_code}\n'
# 204
```

Note for Windows PowerShell: native `curl.exe` mangles JSON double quotes. Prefer `Invoke-RestMethod`
with single-quoted strings.

Deeper spec, if needed: [DATA-MODEL](docs/spec/DATA-MODEL.md),
[API-ENDPOINTS](docs/spec/API-ENDPOINTS.md), [MULTI-USER-AUTH](docs/spec/MULTI-USER-AUTH.md).
