-- Monthly comparison for ONE tenant.
--   ?1 = tenant_id  (from the bound Principal, never from the request)
--   ?2 = asOf       ('YYYY-MM-DD', inclusive)
-- Returns one row per calendar month from the month of comparison_start through the
-- month of asOf. Returns zero rows if the tenant has no settings or asOf < comparison_start.
-- Rides count when region = 'local' and currency = tenant currency (every stored ride is
-- personal by construction). Rentals also need purpose = 'personal'.
-- All money is integer cents. Recurring amounts are summed as exact fractions over the
-- common denominator 7 * days_in_month and rounded half-even ONCE per month and side.
WITH RECURSIVE
s AS (
    SELECT comparison_start AS cs, currency AS cur
      FROM tenant_settings
     WHERE tenant_id = ?1
       AND comparison_start <= ?2
),
months(m_start) AS (
    SELECT date(cs, 'start of month') FROM s
    UNION ALL
    SELECT date(m_start, '+1 month') FROM months
     WHERE date(m_start, '+1 month') <= ?2
),
win AS (
    SELECT m.m_start,
           max(m.m_start, s.cs)                                   AS w_from,
           min(date(m.m_start, '+1 month', '-1 day'), ?2)         AS w_to,
           CAST(strftime('%d', date(m.m_start, '+1 month', '-1 day')) AS INTEGER) AS dim,
           s.cur
      FROM months m CROSS JOIN s
),
cost_days AS (
    SELECT w.m_start, rc.side, rc.period, rc.amount_cents, w.dim,
           max(0, CAST(round(julianday(min(w.w_to, coalesce(rc.effective_to, '9999-12-31')))
                           - julianday(max(w.w_from, rc.effective_from))) AS INTEGER) + 1) AS d
      FROM recurring_cost rc
      JOIN win w
        ON rc.effective_from <= w.w_to
       AND coalesce(rc.effective_to, '9999-12-31') >= w.w_from
     WHERE rc.tenant_id = ?1
),
-- numerator over denominator 7*dim:  monthly: a*d/dim = a*d*7/(7*dim);  weekly: a*d/7 = a*d*dim/(7*dim)
cost_num AS (
    SELECT m_start,
           sum(CASE WHEN side = 'baseline' THEN
                    CASE period WHEN 'monthly' THEN amount_cents * d * 7 ELSE amount_cents * d * dim END
               ELSE 0 END) AS old_num,
           sum(CASE WHEN side = 'current' THEN
                    CASE period WHEN 'monthly' THEN amount_cents * d * 7 ELSE amount_cents * d * dim END
               ELSE 0 END) AS cur_num
      FROM cost_days
     GROUP BY m_start
),
rides AS (
    SELECT w.m_start,
           sum(CASE WHEN r.region = 'local' AND r.currency = w.cur
                    THEN r.total_cents ELSE 0 END)                                    AS ride_cents,
           sum(CASE WHEN r.region = 'local' AND r.currency = w.cur
                    THEN 1 ELSE 0 END)                                                AS ride_count,
           sum(CASE WHEN r.region = 'local' AND r.currency = w.cur
                    THEN 0 ELSE 1 END)                                                AS excluded_ride_count
      FROM ride r
      JOIN win w ON r.ride_date BETWEEN w.w_from AND w.w_to
     WHERE r.tenant_id = ?1
     GROUP BY w.m_start
),
rental_net AS (
    SELECT rn.rental_pk, rn.start_date, rn.purpose, rn.region, rn.currency,
           rn.gross_cents - coalesce((SELECT sum(a.amount_cents)
                                        FROM rental_adjustment a
                                       WHERE a.rental_pk = rn.rental_pk
                                         AND a.affects_net = 1), 0) AS net_cents
      FROM rental rn
     WHERE rn.tenant_id = ?1
),
rentals AS (
    SELECT w.m_start,
           sum(CASE WHEN x.purpose = 'personal' AND x.region = 'local' AND x.currency = w.cur
                    THEN x.net_cents ELSE 0 END)                                      AS rental_cents,
           sum(CASE WHEN x.purpose = 'personal' AND x.region = 'local' AND x.currency = w.cur
                    THEN 1 ELSE 0 END)                                                AS rental_count,
           sum(CASE WHEN x.purpose = 'personal' AND x.region = 'local' AND x.currency = w.cur
                    THEN 0 ELSE 1 END)                                                AS excluded_rental_count
      FROM rental_net x
      JOIN win w ON x.start_date BETWEEN w.w_from AND w.w_to
     GROUP BY w.m_start
),
joined AS (
    SELECT w.m_start, w.w_from, w.w_to, w.dim, 7 * w.dim AS den,
           coalesce(c.old_num, 0) AS old_num,
           coalesce(c.cur_num, 0) AS cur_num,
           coalesce(r.ride_cents, 0) AS ride_cents,
           coalesce(r.ride_count, 0) AS ride_count,
           coalesce(r.excluded_ride_count, 0) AS excluded_ride_count,
           coalesce(t.rental_cents, 0) AS rental_cents,
           coalesce(t.rental_count, 0) AS rental_count,
           coalesce(t.excluded_rental_count, 0) AS excluded_rental_count
      FROM win w
      LEFT JOIN cost_num c ON c.m_start = w.m_start
      LEFT JOIN rides    r ON r.m_start = w.m_start
      LEFT JOIN rentals  t ON t.m_start = w.m_start
),
rounded AS (
    -- round half-even of n/den for n >= 0:  q = n/den, rem = n%den
    SELECT j.*,
           (old_num / den) + CASE WHEN 2 * (old_num % den) > den THEN 1
                                  WHEN 2 * (old_num % den) = den THEN (old_num / den) % 2
                                  ELSE 0 END AS old_cents,
           (cur_num / den) + CASE WHEN 2 * (cur_num % den) > den THEN 1
                                  WHEN 2 * (cur_num % den) = den THEN (cur_num / den) % 2
                                  ELSE 0 END AS recurring_new_cents
      FROM joined j
)
SELECT strftime('%Y-%m', m_start)                         AS month,
       w_from                                             AS window_from,
       w_to                                               AS window_to,
       CAST(round(julianday(w_to) - julianday(w_from)) AS INTEGER) + 1 AS days,
       old_cents,
       ride_cents,
       ride_count,
       rental_cents,
       rental_count,
       recurring_new_cents,
       ride_cents + rental_cents + recurring_new_cents   AS new_cents,
       old_cents - (ride_cents + rental_cents + recurring_new_cents) AS saved_cents,
       excluded_ride_count,
       excluded_rental_count
  FROM rounded
 ORDER BY m_start;
