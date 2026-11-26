import { describe, expect, it } from 'vitest';
import {
  countContext,
  countsRental,
  countsRide,
  fmtMoney,
  monthLabel,
  type CountContext,
} from './data';

const CTX: CountContext = {
  comparisonStart: '2026-07-26',
  asOf: '2026-11-25',
  currency: 'USD',
};

describe('fmtMoney', () => {
  it('formats dollars and cents', () => {
    expect(fmtMoney(66.45)).toBe('$66.45');
  });

  it('formats zero', () => {
    expect(fmtMoney(0)).toBe('$0.00');
  });

  it('formats negatives', () => {
    expect(fmtMoney(-500)).toBe('-$500.00');
  });

  it('honors currency', () => {
    expect(fmtMoney(25, 'CAD')).toBe('CA$25.00');
  });
});

describe('monthLabel', () => {
  it('shortens year-months', () => {
    expect(monthLabel('2026-11')).toBe('Nov 26');
  });

  it('passes garbage through', () => {
    expect(monthLabel('soon')).toBe('soon');
  });
});

describe('countsRide', () => {
  const ride = { rideDate: '2026-11-24', region: 'local', currency: 'USD' };

  it('counts in-window local rides', () => {
    expect(countsRide(ride, CTX)).toBe(true);
  });

  it('excludes travel rides', () => {
    expect(countsRide({ ...ride, region: 'travel' }, CTX)).toBe(false);
  });

  it('excludes foreign currency', () => {
    expect(countsRide({ ...ride, currency: 'CAD' }, CTX)).toBe(false);
  });

  it('excludes pre-start rides', () => {
    expect(countsRide({ ...ride, rideDate: '2026-07-18' }, CTX)).toBe(false);
  });

  it('excludes post-asOf rides', () => {
    expect(countsRide({ ...ride, rideDate: '2026-11-26' }, CTX)).toBe(false);
  });

  it('includes boundary days', () => {
    expect(countsRide({ ...ride, rideDate: '2026-07-26' }, CTX)).toBe(true);
    expect(countsRide({ ...ride, rideDate: '2026-11-25' }, CTX)).toBe(true);
  });
});

describe('countsRental', () => {
  const rental = {
    startDate: '2026-09-29',
    region: 'local',
    currency: 'USD',
    purpose: 'personal',
  };

  it('counts personal local rentals in window', () => {
    expect(countsRental(rental, CTX)).toBe(true);
  });

  it('excludes business rentals', () => {
    expect(countsRental({ ...rental, purpose: 'business' }, CTX)).toBe(false);
  });

  it('attributes by start date', () => {
    expect(countsRental({ ...rental, startDate: '2026-07-20' }, CTX)).toBe(false);
  });
});

describe('countContext', () => {
  it('derives from a comparison', () => {
    expect(countContext({ comparisonStart: '2026-07-26', asOf: '2026-11-25', currency: 'USD' })).toEqual(
      CTX,
    );
  });
});
