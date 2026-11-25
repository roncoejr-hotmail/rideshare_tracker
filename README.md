# Transport Ledger

Multi-tenant transport-spend ledger. Answers one question per household:
**how much are we saving by giving up our leased cars?** Stores rideshare
rides, car rentals (with rebates, refunds, reimbursements, and holds), and
recurring costs, and computes a monthly old-vs-new comparison.

Plain JDK 25 `HttpServer` + virtual threads + SQLite (`sqlite-jdbc`) + Jackson.
No framework. One Bearer key both opens the API and selects exactly one tenant;
tenants never see each other's data. Auth is the same tenant/key design as the
grocery service.

Spec docs (shipped in `transport-ledger-docs.zip`, archived under `docs/spec/`):
[README](docs/spec/README.md) (index),
[DATA-MODEL](docs/spec/DATA-MODEL.md) (schema, comparison algorithm),
[API-ENDPOINTS](docs/spec/API-ENDPOINTS.md) (routes, validation, acceptance list),
[MULTI-USER-AUTH](docs/spec/MULTI-USER-AUTH.md) (auth model),
[seed-README](docs/spec/seed-README.md) (seed data notes).
Relative links inside those files resolve within the zip. Bots start with
[BOT-GUIDE.md](BOT-GUIDE.md).

## Prerequisites

- JDK 25 and Maven 3.9+. On this machine Maven defaults to an older JDK, so point it at 25 first:
  ```powershell
  $env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-25.0.5.21-hotspot'
  ```
- If Maven Central downloads fail with a TLS `No PSK available` error, force TLS 1.2 for the Maven JVM:
  ```powershell
  $env:MAVEN_OPTS = '-Djdk.tls.client.protocols=TLSv1.2'
  ```

## Build, test, run

```powershell
mvn test            # full suite (72 tests: spec sections 13.1-13.8 plus edge cases)
mvn package         # builds target/transport-ledger-0.1.0.jar (shaded, runnable)
java --enable-native-access=ALL-UNNAMED -jar target/transport-ledger-0.1.0.jar
```

The server listens on `${TRANSPORT_BIND:-127.0.0.1}:${TRANSPORT_PORT:-8080}` and
opens `${TRANSPORT_DB_PATH:-./transport.db}` (WAL mode, `foreign_keys` on,
`busy_timeout` 5s). Migrations run before the port opens; startup fails fast on
a newer-than-known schema or any integrity finding.

Tests drive the production request handler in-process (no sockets), each against
a fresh migrated database. In a sandbox where the compiler cannot read build
output under the workspace, relocate the build directory:
`mvn -Dtransport.buildDir=<writable dir> package`.

## First run (local-first bootstrap)

1. Start the API locally against a fresh, empty database.
2. Call the bootstrap route once (no auth; dead after the first tenant exists):
   ```powershell
   Invoke-RestMethod -Method Post http://localhost:8080/api/bootstrap `
     -ContentType 'application/json' -Body '{"name":"Ron","keyLabel":"desktop"}'
   ```
   The response shows the operator key **once**. Save it; the server keeps only the SHA-256 hash.
3. Load the real history into tenant 1 (the seed assumes tenant 1 exists):
   ```powershell
   sqlite3 transport.db < src/test/resources/seed/seed.sql
   ```
   The seed is idempotent (`ON CONFLICT DO NOTHING`); applying it twice is safe.
   Verify: `GET /api/comparison?asOf=2026-11-25` should total
   old $6,266.59 / new $2,344.44 / saved $3,922.15.
4. Deploy the container with this database file seeding its volume. The same key
   works in the cloud, and the bootstrap route is already dead there.

From the moment you bootstrap, that database file is production state: do not
commit it (it holds real spending history), back it up
(`VACUUM INTO` while running, or a file copy while stopped), and use a separate
empty database for throwaway dev testing. Create tenants for bots via
`POST /api/admin/tenants` with the operator key; each bot gets its own key.

## Docker

```powershell
docker build -t transport-ledger .
docker run -p 8080:8080 -v transport-data:/data transport-ledger
```

The image stores the database at `/data/transport.db`
(`TRANSPORT_DB_PATH`, binds `0.0.0.0` in-container). To deploy a locally
bootstrapped database, copy your `transport.db` into the volume before first
start. The image runs as UID/GID 10002 (`app`), not root; a bind-mounted data
directory on the host must be owned accordingly:
`chown 10002:10002 <host-data-dir>` (plus `:Z` on SELinux-enforcing hosts).

## Droplet deployment

`transport.conf` mirrors `grocery.conf`: TLS termination, `/health` and `/api/`
proxied to `http://transport-api:8080`, `10r/s` limit on `/api/`, everything
else 404. The hostname in it is `transportation-history.miosys.org` (A record created 2026-11-25).

1. ~~Create the **A record**~~ done: `transportation-history.miosys.org` (2026-11-25).
   Verify it resolves to the droplet: `nslookup transportation-history.miosys.org`.
2. On the droplet: add the service to the stack (image + `/data` volume on the
   proxy network as `transport-api`), install `transport.conf` into nginx.
3. Issue the certificate (DNS must resolve first):
   `certbot certonly --webroot -w /var/www/certbot -d transportation-history.miosys.org`
   (adjust to the droplet's certbot setup), then reload nginx.

## API notes

- Auth: `Authorization: Bearer <secret>` on everything except `GET /health`
  and the one-time `POST /api/bootstrap`. Failures return `401` with
  `{"error":"unauthorized - missing or invalid API key"}`.
- Tenant admin (`/api/admin/tenants…`, `/api/admin/keys/…`) is operator-only;
  anyone else gets `403 {"error":"forbidden"}`. Any tenant may rotate its own
  presented key via `PATCH /api/admin/tenant/key`.
- `PUT` upserts by natural key (201 created, 200 replaced); the path is
  authoritative. `PATCH` merges scalars; explicit `null`s and unknown fields are
  ignored; an `adjustments` array replaces the whole set.
- Rides are personal-only: every ride write needs `emailSubject` containing the
  tenant's personal tag (default `[Personal]`), else `400`. Rentals carry
  `purpose` instead and have no subject requirement.
- Money is JSON numbers with exactly 2 decimals in responses; integer cents in
  storage. The comparison rounds once per month per side (half-even);
  cumulative values sum the rounded months so the table adds up.
- Another tenant's row is invisible: reads, patches, and deletes against it
  return `404`, same as a missing row.

## Layout

```
src/main/java/org/miosys/transport/
  App.java            main: config -> migrations -> HttpServer
  config/             env config (TRANSPORT_DB_PATH/BIND/PORT)
  db/                 connections, migrations runner, SQL splitter, transactions
  auth/               door, principal, bearer parse, SHA-256, secrets, key/tenant repos
  http/               handler pipeline, router, path codec, JSON, validator,
                      PUT/PATCH input mapping, views, route handlers
  domain/             money, enums, ride, rental, adjustments, recurring cost,
                      settings, pages, comparison report
  store/              tenant-scoped SQLite stores, tenant admin, key rotation,
                      comparison query
src/main/resources/
  db/migration/V1__schema.sql     exact spec DDL
  sql/comparison.sql              exact spec comparison query
src/test/java/org/miosys/transport/
  11 test classes covering spec sections 13.1-13.8 plus edge cases
src/test/resources/seed/
  seed.sql              real tenant-1 history (sensitive: real spending data)
  fixture-synthetic.sql invented rule-coverage fixture
```

Dependency notes: `slf4j-simple` stands in for the spec's `slf4j-jdk14` (same
role: a concrete log binding); Jackson is 2.18.3 and JUnit is 6.1.3 per locally
available versions. Behavior follows the spec, not the version numbers.
