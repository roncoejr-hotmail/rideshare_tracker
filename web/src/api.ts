export interface Ride {
  emailMessageId: string;
  provider: string;
  region: string;
  rideDate: string;
  pickupUtc: string | null;
  dropoffUtc: string | null;
  pickupAddr: string | null;
  dropoffAddr: string | null;
  currency: string;
  fare: number;
  tip: number;
  total: number;
  paymentLast4: string | null;
  rider: string | null;
  emailSubject: string;
  emailReceivedUtc: string | null;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Adjustment {
  lineNo: number;
  kind: string;
  amount: number;
  affectsNet: boolean;
  effectiveDate: string;
  holdReleased: boolean | null;
  emailMessageId: string | null;
  notes: string | null;
}

export interface Rental {
  company: string;
  confirmationNo: string;
  bookingChannel: string | null;
  startDate: string;
  endDate: string;
  location: string | null;
  purpose: string;
  region: string;
  currency: string;
  gross: number;
  net: number;
  paymentLast4: string | null;
  emailMessageId: string | null;
  emailSubject: string | null;
  notes: string | null;
  adjustments: Adjustment[];
  createdAt: string;
  updatedAt: string;
}

export interface Settings {
  comparisonStart: string;
  currency: string;
  homeRegion: string;
  personalTag: string;
  timeZone: string;
  createdAt: string;
  updatedAt: string;
}

export interface MonthRow {
  month: string;
  windowFrom: string;
  windowTo: string;
  days: number;
  oldCost: number;
  rides: number;
  rideCount: number;
  rentals: number;
  rentalCount: number;
  recurringNew: number;
  newCost: number;
  saved: number;
  excludedRides: number;
  excludedRentals: number;
  cumulativeOldCost: number;
  cumulativeNewCost: number;
  cumulativeSaved: number;
}

export interface Totals {
  days: number;
  oldCost: number;
  newCost: number;
  saved: number;
  rideCount: number;
  rentalCount: number;
  excludedRides: number;
  excludedRentals: number;
}

export interface Comparison {
  currency: string;
  comparisonStart: string;
  asOf: string;
  months: MonthRow[];
  totals: Totals;
}

interface Page<T> {
  content: T[];
  page: number;
  size: number;
  total: number;
}

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export const API_BASE: string =
  import.meta.env.VITE_API_BASE ?? 'https://transportation-history.miosys.org';

async function request(base: string, key: string, path: string): Promise<Response> {
  let res: Response;
  try {
    res = await fetch(`${base}${path}`, { headers: { Authorization: `Bearer ${key}` } });
  } catch {
    throw new ApiError(0, 'Network error: could not reach the API.');
  }
  return res;
}

async function errorDetail(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { error?: unknown };
    if (typeof body.error === 'string' && body.error.length > 0) {
      return body.error;
    }
  } catch {
    // fall through to the generic message
  }
  return `Request failed (${res.status}).`;
}

async function getJson<T>(base: string, key: string, path: string): Promise<T> {
  const res = await request(base, key, path);
  if (res.status === 401) {
    throw new ApiError(401, 'Invalid API key.');
  }
  if (!res.ok) {
    throw new ApiError(res.status, await errorDetail(res));
  }
  return (await res.json()) as T;
}

async function fetchAll<T>(base: string, key: string, path: string): Promise<T[]> {
  const size = 200;
  const first = await getJson<Page<T>>(base, key, `${path}?page=0&size=${size}`);
  const items = [...first.content];
  const pages = Math.ceil(first.total / size);
  for (let page = 1; page < pages; page++) {
    const next = await getJson<Page<T>>(base, key, `${path}?page=${page}&size=${size}`);
    items.push(...next.content);
  }
  return items;
}

export function fetchAllRides(base: string, key: string): Promise<Ride[]> {
  return fetchAll<Ride>(base, key, '/api/rides');
}

export function fetchAllRentals(base: string, key: string): Promise<Rental[]> {
  return fetchAll<Rental>(base, key, '/api/rentals');
}

export function fetchComparison(base: string, key: string): Promise<Comparison> {
  return getJson<Comparison>(base, key, '/api/comparison');
}

export async function fetchSettings(base: string, key: string): Promise<Settings | null> {
  try {
    return await getJson<Settings>(base, key, '/api/settings');
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      return null;
    }
    throw e;
  }
}
