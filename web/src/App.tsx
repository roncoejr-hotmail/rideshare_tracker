import { useState } from 'react';
import {
  API_BASE,
  ApiError,
  fetchAllRentals,
  fetchAllRides,
  fetchComparison,
  fetchSettings,
  type Comparison,
  type Rental,
  type Ride,
  type Settings,
} from './api';
import { fmtMoney } from './data';
import KeyGate from './components/KeyGate';
import RentalsTable from './components/RentalsTable';
import RidesTable from './components/RidesTable';
import SavingsChart from './components/SavingsChart';
import SummaryTable from './components/SummaryTable';

type Tab = 'summary' | 'rides' | 'rentals';

const TABS: { id: Tab; label: string }[] = [
  { id: 'summary', label: 'Monthly summary' },
  { id: 'rides', label: 'Rides' },
  { id: 'rentals', label: 'Rentals' },
];

export default function App() {
  const [key, setKey] = useState<string | null>(null);
  const [rides, setRides] = useState<Ride[] | null>(null);
  const [rentals, setRentals] = useState<Rental[] | null>(null);
  const [comparison, setComparison] = useState<Comparison | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [needsSetup, setNeedsSetup] = useState(false);
  const [checking, setChecking] = useState(false);
  const [gateError, setGateError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('summary');

  async function connect(nextKey: string) {
    setChecking(true);
    setGateError(null);
    try {
      const s = await fetchSettings(API_BASE, nextKey);
      if (s === null) {
        setKey(nextKey);
        setSettings(null);
        setRides([]);
        setRentals([]);
        setComparison(null);
        setNeedsSetup(true);
        return;
      }
      const [r, t, c] = await Promise.all([
        fetchAllRides(API_BASE, nextKey),
        fetchAllRentals(API_BASE, nextKey),
        fetchComparison(API_BASE, nextKey),
      ]);
      setSettings(s);
      setRides(r);
      setRentals(t);
      setComparison(c);
      setNeedsSetup(false);
      setKey(nextKey);
    } catch (e) {
      setGateError(e instanceof ApiError ? e.message : 'Could not reach the API.');
    } finally {
      setChecking(false);
    }
  }

  function forget() {
    setKey(null);
    setRides(null);
    setRentals(null);
    setComparison(null);
    setSettings(null);
    setNeedsSetup(false);
    setChecking(false);
    setGateError(null);
    setTab('summary');
  }

  if (key === null) {
    return <KeyGate checking={checking} error={gateError} onSubmit={connect} />;
  }

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <h1>Transport Ledger</h1>
          <p className="sub">
            {comparison === null
              ? API_BASE.replace('https://', '')
              : `${rides?.length ?? 0} rides · ${rentals?.length ?? 0} rentals · saved ${fmtMoney(comparison.totals.saved, comparison.currency)} through ${comparison.asOf}`}
          </p>
        </div>
        <button type="button" className="ghost" onClick={forget}>
          Forget key
        </button>
      </header>

      {needsSetup || comparison === null || rides === null || rentals === null ? (
        <section className="panel" aria-label="Setup needed">
          <h2>No settings yet</h2>
          <p>
            This tenant has no comparison settings, so there is nothing to show. Create them with{' '}
            <span className="mono">PUT /api/settings</span> (see BOT-GUIDE.md), then reconnect.
          </p>
          <p className="note">
            Local means{' '}
            {settings === null ? 'your home region' : <strong>{settings.homeRegion}</strong>}.
          </p>
          <button
            type="button"
            className="ghost"
            disabled={checking}
            onClick={() => {
              void connect(key);
            }}
          >
            {checking ? 'Loading…' : 'Retry'}
          </button>
        </section>
      ) : (
        <>
          <div className="tabs" role="tablist" aria-label="Ledger views">
            {TABS.map((t) => (
              <button
                key={t.id}
                type="button"
                role="tab"
                aria-selected={tab === t.id}
                className="tab"
                onClick={() => setTab(t.id)}
              >
                {t.label}
              </button>
            ))}
          </div>

          {tab === 'summary' && (
            <>
              <section className="panel" aria-label="Monthly costs and savings">
                <h2>
                  Monthly costs and savings · {comparison.currency} · local means{' '}
                  {settings?.homeRegion ?? '—'}
                </h2>
                <SavingsChart months={comparison.months} currency={comparison.currency} />
              </section>
              <section className="panel" aria-label="Monthly summary">
                <h2>Monthly summary</h2>
                <SummaryTable comparison={comparison} />
              </section>
            </>
          )}

          {tab === 'rides' && (
            <section className="panel" aria-label="Rides">
              <h2>Rides</h2>
              <RidesTable rides={rides} comparison={comparison} />
            </section>
          )}

          {tab === 'rentals' && (
            <section className="panel" aria-label="Rentals">
              <h2>Rentals</h2>
              <RentalsTable rentals={rentals} comparison={comparison} />
            </section>
          )}
        </>
      )}

      <footer>
        <span>Key held in memory only — forgetting clears everything.</span>
      </footer>
    </div>
  );
}
