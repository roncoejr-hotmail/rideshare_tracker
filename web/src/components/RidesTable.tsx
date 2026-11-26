import type { Comparison, Ride } from '../api';
import { countContext, countsRide, fmtMoney } from '../data';

export default function RidesTable({
  rides,
  comparison,
}: {
  rides: Ride[];
  comparison: Comparison;
}) {
  if (rides.length === 0) {
    return <p className="empty">No rides yet.</p>;
  }
  const ctx = countContext(comparison);
  const total = rides.reduce((a, r) => a + r.total, 0);
  return (
    <>
      <p className="note">
        {rides.length} rides · {fmtMoney(total, comparison.currency)} charged
      </p>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Provider</th>
              <th className="num">Total</th>
              <th className="num">Tip</th>
              <th>Cur</th>
              <th>Region</th>
              <th>Pickup</th>
              <th>Dropoff</th>
              <th>Pay</th>
              <th>Counts</th>
              <th>Notes</th>
            </tr>
          </thead>
          <tbody>
            {rides.map((r) => (
              <tr key={r.emailMessageId}>
                <td>{r.rideDate}</td>
                <td>{r.provider}</td>
                <td className="num">{fmtMoney(r.total, r.currency)}</td>
                <td className="num">{fmtMoney(r.tip, r.currency)}</td>
                <td>{r.currency}</td>
                <td>{r.region}</td>
                <td>{r.pickupAddr ?? '—'}</td>
                <td>{r.dropoffAddr ?? '—'}</td>
                <td>{r.paymentLast4 === null ? '—' : `··· ${r.paymentLast4}`}</td>
                <td>{countsRide(r, ctx) ? 'Yes' : '—'}</td>
                <td>{r.notes ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
