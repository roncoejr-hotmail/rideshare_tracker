# Transport Ledger Dashboard

React + Vite + TypeScript dashboard over the Transport Ledger API: the Rides
tab, the Rentals tab (with expandable adjustments), and the Monthly Summary tab
(month table plus an old-vs-new cost chart with a savings line).

## Run it

```powershell
cd web
npm install
npm run dev
```

Open http://localhost:5173 in a browser and paste an API key when asked.
The key lives in memory only — leaving the page or pressing "Forget key"
clears it. Nothing is ever written to browser storage.

The API defaults to `https://transportation-history.miosys.org`. Point at another
deployment with `VITE_API_BASE` (read at dev/build time):

```powershell
$env:VITE_API_BASE = 'http://localhost:8080'; npm run dev
```

## API prerequisite: CORS

The browser calls the API cross-origin, so the API must allow this page's
origin. Set on the API side (comma-separated for several):

```text
TRANSPORT_CORS_ORIGINS=http://localhost:5173
```

Without it, every API call fails in the browser (the page loads, data does
not). Same-origin hosting needs no setting.

## Checks

```powershell
npm test        # data-layer unit tests (vitest)
npm run lint    # oxlint
npm run build   # typecheck + production bundle in dist/
```

## Layout

```text
src/
  api.ts          typed client (base URL, paging, auth errors)
  data.ts         pure display helpers (money, month labels, counts rules)
  data.test.ts    unit tests for data.ts
  App.tsx         key gate + tab shell + data loading
  components/     KeyGate, RidesTable, RentalsTable, SummaryTable, SavingsChart
  index.css       hand stylesheet (no framework)
```
