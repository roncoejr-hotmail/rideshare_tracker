# Seed data for tenant 1

`seed.sql` loads the real transportation history from the current tracker spreadsheet (tabs **Rides** and **Rentals**, exported 2026-11-25) into tenant 1, plus tenant 1's settings and recurring costs. It contains no API keys, hashes, or secrets.

## Files

| File | What it is |
| --- | --- |
| `seed.sql` | Generated INSERTs for `tenant_settings`, `recurring_cost`, `ride` (28), `rental` (3), `rental_adjustment` (3). |
| `gen_seed.py` | Generator. Input: a CSV export of the Rides tab; the home street name to shorten is passed in `SEED_HOME_STREET` so it is not stored here. Rentals are written from the 3 rows of the Rentals tab (hard-coded, with shortened notes). Not needed by the implementation. |
| `validate.py` | Builds an in-memory DB from the DDL, inserts tenant 1, applies `seed.sql` **twice** (idempotency), runs `../sql/comparison.sql`, prints the month table. |
| `fixture-synthetic.sql` | Invented test data for tenants 2 and 3 (rule coverage and a half-even tie). Not real spending. Expected results: API-ENDPOINTS.md §13.8. |

## How to load

```bash
# Option A: through the running service (preferred)
#   1. Start the server on an empty DB; POST /api/bootstrap  -> tenant 1 (operator)
#   2. Stop the server (or use a second connection; WAL allows it) and run:
sqlite3 transport.db < seed/seed.sql

# Option B: standalone check, no server
python3 seed/validate.py sql/V1__schema.sql seed/seed.sql sql/comparison.sql 2026-11-25
```

`seed.sql` assumes `tenant_id = 1` exists. Every insert uses `ON CONFLICT ... DO NOTHING`, so applying it twice is harmless, but it will not overwrite rows that changed since.

## Mapping from the spreadsheet

| Sheet | Column(s) | Store |
| --- | --- | --- |
| Rides | Date | `ride.ride_date` |
| Rides | Service | `provider` |
| Rides | Amount | `total` (charged, after credits, including tip) |
| Rides | Notes "includes tip USD/CAD x" | `tip_cents`; `fare_cents = total − tip`. No tip mentioned → tip 0. |
| Rides | Currency | `currency` (USD or CAD) |
| Rides | Tag `Personal` | Not stored (rides have no purpose column). Every sheet ride is tagged Personal; the generator stops if one is not. |
| Rides | Region `DMV` / `Travel` | `region = 'local'` / `'travel'` |
| Rides | Pickup, Dropoff | `pickup_addr`, `dropoff_addr`. The home street address is reduced to `Bowie, MD`; `unknown` → NULL. Other addresses are businesses or venues and are kept as in the sheet. |
| Rides | Notes "pickup h:mm AM, dropoff h:mm AM" (2 rides) | `pickup_utc`, `dropoff_utc` (converted from Eastern Standard Time, UTC−5) |
| Rides | Payment | `payment_last4` (last 4 digits only) |
| Rides | Email Subject | `email_subject` (required). All 28 sheet subjects already contain `[Personal]`, so **no subject was synthesized**. If a future export has a subject without the tag, `gen_seed.py` writes `[Personal] <Service> trip on <Date> (subject synthesized by seed)` and lists the affected dates in its output. |
| Rides | Notes | `notes` (verbatim) |
| Rides | "In Comparison Window" | not stored; the rules derive it |
| Rentals | Pickup/Return Date, Company, Booked Via, Gross Cost | `start_date`, `end_date`, `company`, `booking_channel`, `gross_cents` |
| Rentals | Confirmation number (from Notes) | `confirmation_no`: Avis `25158422US2`, Turo `61075405`, Budget `06832678US6` |
| Rentals | Tag `untagged` | `purpose = 'personal'` (the sheet treats them as personal; rentals have no subject requirement) |
| Rentals | Rebate/Credit and Notes | `rental_adjustment` rows, below |

Rental adjustments:

| Rental | kind | amount | affects net | effective | Source note |
| --- | --- | --- | --- | --- | --- |
| Avis | hold | $300.00 | no | 2026-09-05 | Refundable deposit mentioned at booking; no release email, so `hold_released = 0`. |
| Turo | reimbursement | $16.00 | no | 2026-09-25 | Parking paid out of pocket and reimbursed by Turo; not in gross, net zero. |
| Budget | rebate | $50.00 | yes | 2026-11-11 | Airline travel credit earned by the rental (not cash). Net $418.93. |

### Email message ids

The sheet does not store provider message ids. Each seeded ride gets a deterministic placeholder:

```text
email_message_id = "sheet:ride:" + first 24 hex chars of
                   SHA-256( Date | Service | Amount | Currency | Pickup | Dropoff | Email Subject )
```

computed over the raw sheet values (before the address was shortened). The same sheet row always produces the same id. If real message ids are later backfilled through the API, the placeholder rows must be deleted first (`DELETE /api/v1/rides/sheet:ride:...`) or the ride will exist twice under two keys. Rentals have no email ids in the seed (`email_message_id` NULL); their natural key is the confirmation number.

### Rows the rules exclude (included on purpose)

- 2 rides on 2026-07-18, before `comparison_start` (2026-07-26): ignored.
- 4 CAD rides (Vancouver): stored in CAD, never converted, excluded (also `travel`).
- 8 USD `travel` rides (Bay Area, San Juan): excluded.

## Validation result (2026-11-25)

Fresh DB → `V1__schema.sql` → tenant 1 → `seed.sql` (twice) → `comparison.sql` with `asOf = 2026-11-25`:

```text
month    window                  days        old     rides  #   rentals      EV        new      saved  cum saved xr
2026-07  2026-07-26..2026-07-31    6     301.73     73.73  1      0.00    6.86      80.59     221.14     221.14  0
2026-08  2026-08-01..2026-08-31   31   1,558.92    225.55  3      0.00   35.43     260.98   1,297.94   1,519.08  6
2026-09  2026-09-01..2026-09-30   30   1,552.92    175.00  4    957.54   34.29   1,166.83     386.09   1,905.17  1
2026-10  2026-10-01..2026-10-31   31   1,558.92    110.45  2      0.00   35.43     145.88   1,413.04   3,318.21  3
2026-11  2026-11-01..2026-11-25   25   1,294.10    242.66  4    418.93   28.57     690.16     603.94   3,922.15  2
cumulative old 6,266.59 new 2,344.44 saved 3,922.15
```

### Comparison with the spreadsheet

Re-run after the personal-only ride change (purpose column dropped, subject required): identical output.

Every **monthly** figure (old cost, rides, rentals, EV charging, new cost, savings, ride counts, excluded travel ride counts) matches the sheet's Monthly Summary exactly.

The **cumulative** total differs by one cent:

| | Sheet | Store | Difference |
| --- | --- | --- | --- |
| Cumulative old cost | $6,266.59 | $6,266.59 | — |
| Cumulative new cost | $2,344.43 | $2,344.44 | +$0.01 |
| Cumulative savings | $3,922.16 | $3,922.15 | −$0.01 |

Why: the sheet computes cumulative EV charging once over the whole window (123 days × $8/7 = $140.571… → $140.57), while the store adds the five rounded monthly EV amounts ($6.86 + $35.43 + $34.29 + $35.43 + $28.57 = $140.58). The store's rule (round once per month, cumulative = sum of rounded months) is chosen so the monthly table always adds up exactly to the totals. Attribution is not a factor here: all three rentals start and end in the same month.
