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
a newer-than-known schema or any integrity finding. Browser dashboards need
`${TRANSPORT_CORS_ORIGINS}` set to the page origin(s), comma-separated
(empty default = no CORS headers).

Tests drive the production request handler in-process (no sockets), each against
a fresh migrated database. In a sandbox where the compiler cannot read build
output under the workspace, relocate the build directory:
`mvn -Dtransport.buildDir=<writable dir> package`.

## Production database (already built)

- `transport.db` (repo root, gitignored): schema v1, operator tenant `Ron` with
  one `desktop` key, and the real history (28 rides, 3 rentals, 3 adjustments,
  4 recurring costs). Verified 2026-11-25: comparison through 2026-11-25 totals
  old $6,266.59 / new $2,344.44 / saved $3,922.15, and the file holds only the
  key hash, never the secret.
- `transport-operator-key.txt` (repo root, gitignored): the operator key, shown
  once. Move it to your secret store and delete this copy when deployed.
- To rebuild from scratch: start the server on an empty DB, `POST /api/bootstrap`,
  then `sqlite3 transport.db < src/test/resources/seed/seed.sql`, then verify the
  comparison totals above.

From the moment of bootstrap, that database file is production state: do not
commit it (it holds real spending history), back it up
(`VACUUM INTO` while running, or a file copy while stopped), and use a separate
empty database for throwaway dev testing. Create tenants for bots via
`POST /api/admin/tenants` with the operator key; each bot gets its own key.

## Docker (local throwaway)

```powershell
docker build -t transport-ledger .
docker run -p 8080:8080 -v transport-data:/data transport-ledger
```

The image stores the database at `/data/transport.db`
(`TRANSPORT_DB_PATH`, binds `0.0.0.0` in-container) and runs as UID/GID 10002
(`app`), not root.

## Droplet deployment

Stack shape (see `cc-engine/deploy/do/ARCHITECTURE.md`): `rev-proxy` is the only
container with host ports; apps publish nothing and join the external network
`do_default` by container name; data lives in block-volume bind mounts at
`/mnt/volume_1784510431131/<service>/`. All docker commands below run from your
Windows box against the remote daemon:

```powershell
$env:DOCKER_HOST = "ssh://root@165.22.38.152"
```

`transport.conf` mirrors the droplet's `grocery.conf` (variable `proxy_pass` +
embedded-DNS resolver, `10r/s` limit on `/api/`). DNS is done:
`transportation-history.miosys.org` A record created 2026-11-25.

1. Copy the nginx conf into the edge (one time; I could not write outside this
   workspace, so this step is yours):
   ```powershell
   Copy-Item transport.conf C:\Users\ronco\_dev\the_difference\cc-engine\deploy\do\nginx\conf.d\transport.conf
   ```
2. Seed the block volume FIRST, before the container ever starts (otherwise Docker
   creates the directory root-owned and the app cannot write):
   ```powershell
   ssh root@165.22.38.152 'mkdir -p /mnt/volume_1784510431131/transport'
   scp transport.db root@165.22.38.152:/mnt/volume_1784510431131/transport/transport.db
   ssh root@165.22.38.152 'chown 10002:10002 /mnt/volume_1784510431131/transport /mnt/volume_1784510431131/transport/transport.db'
   ```
3. From THIS directory, build and start the API (no ports published):
   ```powershell
   docker compose build
   docker compose up -d
   docker exec transport-api curl -fsS http://localhost:8080/health
   ```
4. From `cc-engine/deploy/do`, put a dummy cert in place FIRST (nginx refuses to
   start with missing cert paths, which would take down every site), then rebuild
   and recreate the edge:
   ```powershell
   docker compose -f docker-compose.edge.yml run --rm --entrypoint sh certbot -c "mkdir -p /etc/letsencrypt/live/transportation-history.miosys.org && openssl req -x509 -nodes -newkey rsa:2048 -days 1 -keyout /etc/letsencrypt/live/transportation-history.miosys.org/privkey.pem -out /etc/letsencrypt/live/transportation-history.miosys.org/fullchain.pem -subj '/CN=localhost'"
   docker compose -f docker-compose.edge.yml build nginx
   docker compose -f docker-compose.edge.yml up -d nginx
   curl.exe -i https://grocery-history.miosys.org/health   # other sites unaffected -> 200
   ```
5. Swap the dummy for a real certificate and reload:
   ```powershell
   docker compose -f docker-compose.edge.yml run --rm --entrypoint sh certbot -c "rm -rf /etc/letsencrypt/live/transportation-history.miosys.org /etc/letsencrypt/archive/transportation-history.miosys.org /etc/letsencrypt/renewal/transportation-history.miosys.org.conf"
   docker compose -f docker-compose.edge.yml run --rm --entrypoint certbot certbot certonly --webroot -w /var/www/certbot -d transportation-history.miosys.org --email you@miosys.org --agree-tos --no-eff-email
   docker compose -f docker-compose.edge.yml exec nginx nginx -s reload
   ```
   (Use your address for `--email`. Add `--dry-run --staging` to the `certonly`
   first if you want to rehearse without burning rate limits.)
6. Verify:
   ```powershell
   curl.exe -i https://transportation-history.miosys.org/health   # 200, no key
   $k = Get-Content transport-operator-key.txt | Where-Object { $_.Trim().Length -gt 0 } | Select-Object -First 1
   curl.exe -i -H "Authorization: Bearer $k" "https://transportation-history.miosys.org/api/comparison?asOf=2026-11-25"
   # totals: oldCost 6266.59, newCost 2344.44, saved 3922.15
   ```
   Also confirm the padlock shows a Let's Encrypt issuer (not the dummy).

Renewals are automatic (certbot renews every 12h, nginx reloads every 6h).
Never run any compose `down -v` on this stack: certificates and books are bind
mounts, but `-v` would still drop the legacy named volumes others may reference.

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
web/                    React + Vite + TypeScript dashboard (see web/README.md):
                        Rides / Rentals / Monthly Summary tabs plus the monthly
                        costs-and-savings chart. Key gate holds the key in memory only.
```

Dependency notes: `slf4j-simple` stands in for the spec's `slf4j-jdk14` (same
role: a concrete log binding); Jackson is 2.18.3 and JUnit is 6.1.3 per locally
available versions. Behavior follows the spec, not the version numbers.
