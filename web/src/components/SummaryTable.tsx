import type { Comparison } from '../api';
import { fmtMoney } from '../data';

function shortWindow(from: string, to: string): string {
  return `${from.slice(5)}–${to.slice(5)}`;
}

export default function SummaryTable({ comparison }: { comparison: Comparison }) {
  const { months, totals } = comparison;
  if (months.length === 0) {
    return <p className="empty">No months in this comparison yet.</p>;
  }
  const cur = comparison.currency;
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Month</th>
            <th>Window</th>
            <th className="num">Days</th>
            <th className="num">Old cost</th>
            <th className="num">Rides</th>
            <th className="num">Rentals</th>
            <th className="num">EV charging</th>
            <th className="num">New cost</th>
            <th className="num">Saved</th>
            <th className="num">Cumul. saved</th>
            <th className="num">Excl.</th>
          </tr>
        </thead>
        <tbody>
          {months.map((m) => (
            <tr key={m.month}>
              <td>{m.month}</td>
              <td title={`${m.windowFrom} to ${m.windowTo}`}>
                {shortWindow(m.windowFrom, m.windowTo)}
              </td>
              <td className="num">{m.days}</td>
              <td className="num">{fmtMoney(m.oldCost, cur)}</td>
              <td className="num" title={`${m.rideCount} rides`}>
                {fmtMoney(m.rides, cur)}
              </td>
              <td className="num" title={`${m.rentalCount} rentals`}>
                {fmtMoney(m.rentals, cur)}
              </td>
              <td className="num">{fmtMoney(m.recurringNew, cur)}</td>
              <td className="num">{fmtMoney(m.newCost, cur)}</td>
              <td className={`num ${m.saved < 0 ? 'negative' : 'positive'}`}>
                {fmtMoney(m.saved, cur)}
              </td>
              <td className={`num ${m.cumulativeSaved < 0 ? 'negative' : 'positive'}`}>
                {fmtMoney(m.cumulativeSaved, cur)}
              </td>
              <td className="num" title="excluded rides / excluded rentals">
                {m.excludedRides}/{m.excludedRentals}
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <td>Total</td>
            <td>—</td>
            <td className="num">{totals.days}</td>
            <td className="num">{fmtMoney(totals.oldCost, cur)}</td>
            <td className="num">—</td>
            <td className="num">—</td>
            <td className="num">—</td>
            <td className="num">{fmtMoney(totals.newCost, cur)}</td>
            <td className={`num ${totals.saved < 0 ? 'negative' : 'positive'}`}>
              {fmtMoney(totals.saved, cur)}
            </td>
            <td className="num">—</td>
            <td className="num">
              {totals.excludedRides}/{totals.excludedRentals}
            </td>
          </tr>
        </tfoot>
      </table>
    </div>
  );
}
