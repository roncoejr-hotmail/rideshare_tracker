import type { Comparison, Rental, Ride } from './api';

export function fmtMoney(v: number, currency = 'USD'): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(v);
}

const MONTHS = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
];

export function monthLabel(yearMonth: string): string {
  const [y, m] = yearMonth.split('-');
  const idx = Number(m) - 1;
  if (y === undefined || m === undefined || !Number.isInteger(idx) || idx < 0 || idx > 11) {
    return yearMonth;
  }
  return `${MONTHS[idx]} ${y.slice(2)}`;
}

export interface CountContext {
  comparisonStart: string;
  asOf: string;
  currency: string;
}

export function countContext(
  c: Pick<Comparison, 'comparisonStart' | 'asOf' | 'currency'>,
): CountContext {
  return { comparisonStart: c.comparisonStart, asOf: c.asOf, currency: c.currency };
}

/** Mirrors the comparison rules so tables can flag which rows count. */
export function countsRide(
  r: Pick<Ride, 'rideDate' | 'region' | 'currency'>,
  ctx: CountContext,
): boolean {
  return (
    r.region === 'local' &&
    r.currency === ctx.currency &&
    r.rideDate >= ctx.comparisonStart &&
    r.rideDate <= ctx.asOf
  );
}

export function countsRental(
  r: Pick<Rental, 'startDate' | 'region' | 'currency' | 'purpose'>,
  ctx: CountContext,
): boolean {
  return (
    r.purpose === 'personal' &&
    r.region === 'local' &&
    r.currency === ctx.currency &&
    r.startDate >= ctx.comparisonStart &&
    r.startDate <= ctx.asOf
  );
}
