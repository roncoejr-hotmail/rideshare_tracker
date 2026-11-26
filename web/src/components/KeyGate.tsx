import { useState } from 'react';

export default function KeyGate({
  checking,
  error,
  onSubmit,
}: {
  checking: boolean;
  error: string | null;
  onSubmit: (key: string) => void;
}) {
  const [value, setValue] = useState('');

  return (
    <div className="gate">
      <form
        className="gate-card"
        onSubmit={(e) => {
          e.preventDefault();
          if (value.trim().length > 0) {
            onSubmit(value.trim());
          }
        }}
      >
        <h1>Transport Ledger</h1>
        <p>
          Paste your API key to load your rides, rentals, and savings comparison. The key lives in
          memory only.
        </p>
        <label>
          API key
          <input
            type="password"
            autoComplete="off"
            spellCheck={false}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            disabled={checking}
          />
        </label>
        {error !== null && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <button type="submit" disabled={checking || value.trim().length === 0}>
          {checking ? 'Connecting…' : 'Connect'}
        </button>
      </form>
    </div>
  );
}
