import { useState } from 'react';
import type { Comparison, Rental } from '../api';
import { countContext, countsRental, fmtMoney } from '../data';

function rentalKey(r: Rental): string {
  return `${r.company}||${r.confirmationNo}`;
}

export default function RentalsTable({
  rentals,
  comparison,
}: {
  rentals: Rental[];
  comparison: Comparison;
}) {
  const [open, setOpen] = useState<string | null>(null);
  if (rentals.length === 0) {
    return <p className="empty">No rentals yet.</p>;
  }
  const ctx = countContext(comparison);
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Company</th>
            <th>Confirmation</th>
            <th>Start</th>
            <th>End</th>
            <th className="num">Gross</th>
            <th className="num">Net</th>
            <th>Region</th>
            <th>Purpose</th>
            <th>Counts</th>
          </tr>
        </thead>
        <tbody>
          {rentals.map((r) => {
            const k = rentalKey(r);
            const expanded = open === k;
            return [
              <tr key={k}>
                <td>
                  <button
                    type="button"
                    className="link"
                    aria-expanded={expanded}
                    onClick={() => setOpen(expanded ? null : k)}
                  >
                    {expanded ? '▾' : '▸'} {r.company}
                  </button>
                </td>
                <td className="mono">{r.confirmationNo}</td>
                <td>{r.startDate}</td>
                <td>{r.endDate}</td>
                <td className="num">{fmtMoney(r.gross, r.currency)}</td>
                <td className="num">{fmtMoney(r.net, r.currency)}</td>
                <td>{r.region}</td>
                <td>{r.purpose}</td>
                <td>{countsRental(r, ctx) ? 'Yes' : '—'}</td>
              </tr>,
              expanded ? (
                <tr key={`${k}::detail`} className="subrow">
                  <td colSpan={9}>
                    <p className="note">
                      {[r.bookingChannel, r.location].filter((v) => v !== null).join(' · ') ||
                        'No booking details.'}
                    </p>
                    {r.adjustments.length === 0 ? (
                      <p className="note">No adjustments.</p>
                    ) : (
                      <table>
                        <thead>
                          <tr>
                            <th>#</th>
                            <th>Kind</th>
                            <th className="num">Amount</th>
                            <th>Net?</th>
                            <th>Effective</th>
                            <th>Released</th>
                            <th>Notes</th>
                          </tr>
                        </thead>
                        <tbody>
                          {r.adjustments.map((a) => (
                            <tr key={a.lineNo}>
                              <td>{a.lineNo}</td>
                              <td>{a.kind}</td>
                              <td className="num">{fmtMoney(a.amount, r.currency)}</td>
                              <td>{a.affectsNet ? 'Yes' : 'No'}</td>
                              <td>{a.effectiveDate}</td>
                              <td>{a.holdReleased === null ? '—' : a.holdReleased ? 'Yes' : 'No'}</td>
                              <td>{a.notes ?? '—'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </td>
                </tr>
              ) : null,
            ];
          })}
        </tbody>
      </table>
    </div>
  );
}
