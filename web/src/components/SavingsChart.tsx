import { useSyncExternalStore } from 'react';
import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { MonthRow } from '../api';
import { fmtMoney, monthLabel } from '../data';

const GRID = '#e3e6e3';
const TICK = { fontSize: 12 };

function useReducedMotion(): boolean {
  return useSyncExternalStore(
    (cb) => {
      const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
      mq.addEventListener('change', cb);
      return () => mq.removeEventListener('change', cb);
    },
    () => window.matchMedia('(prefers-reduced-motion: reduce)').matches,
  );
}

function shortMoney(v: unknown): string {
  const n = Number(v);
  if (Math.abs(n) >= 1000) {
    return `$${(Math.round(n / 100) / 10).toFixed(1)}k`;
  }
  return `$${Math.round(n)}`;
}

export default function SavingsChart({
  months,
  currency,
}: {
  months: MonthRow[];
  currency: string;
}) {
  const animate = !useReducedMotion();
  if (months.length === 0) {
    return <p className="empty">No months in this comparison yet.</p>;
  }
  const data = months.map((m) => ({
    label: monthLabel(m.month),
    old: m.oldCost,
    current: m.newCost,
    saved: m.saved,
  }));
  return (
    <ResponsiveContainer width="100%" height={300}>
      <ComposedChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke={GRID} />
        <XAxis dataKey="label" tick={TICK} minTickGap={20} />
        <YAxis tickFormatter={(v) => shortMoney(v)} tick={TICK} width={56} />
        <Tooltip formatter={(v) => fmtMoney(Number(v), currency)} />
        <Legend wrapperStyle={{ fontSize: 12 }} />
        <Bar dataKey="old" name="Old cost" fill="#64748b" isAnimationActive={animate} />
        <Bar dataKey="current" name="New cost" fill="#0e7490" isAnimationActive={animate} />
        <Line
          type="monotone"
          dataKey="saved"
          name="Saved"
          stroke="#166534"
          strokeWidth={2}
          dot={false}
          isAnimationActive={animate}
        />
      </ComposedChart>
    </ResponsiveContainer>
  );
}
