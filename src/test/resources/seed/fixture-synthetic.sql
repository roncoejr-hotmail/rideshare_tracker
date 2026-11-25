-- Synthetic acceptance fixture (NOT real data). Tenant 2 "Fixture".
INSERT INTO tenant (tenant_id, name) VALUES (2, 'Fixture');
INSERT INTO tenant_settings (tenant_id, comparison_start, currency, home_region, personal_tag, time_zone)
VALUES (2, '2026-09-15', 'USD', 'Testville', '[Personal]', 'UTC');
INSERT INTO recurring_cost (tenant_id, name, side, period, amount_cents, effective_from, effective_to) VALUES
  (2, 'Lease',       'baseline', 'monthly', 300000, '2026-09-15', NULL),
  (2, 'Fuel avoided','baseline', 'weekly',    3500, '2026-09-15', '2026-10-05'),
  (2, 'EV charging', 'current',  'weekly',     700, '2026-09-20', NULL);
INSERT INTO ride (tenant_id, email_message_id, provider, region, ride_date, currency, fare_cents, tip_cents, email_subject) VALUES
  (2, 'fx-1', 'Uber', 'local',  '2026-09-20', 'USD', 1000, 200, '[Personal] fx-1'),  -- counts (Sep): 12.00
  (2, 'fx-2', 'Uber', 'local',  '2026-09-10', 'USD', 5000,   0, '[Personal] fx-2'),  -- before comparison_start: ignored
  (2, 'fx-3', 'Uber', 'travel', '2026-09-21', 'USD', 4000,   0, '[Personal] fx-3'),  -- travel: excluded
  (2, 'fx-5', 'Uber', 'local',  '2026-10-01', 'CAD', 2500,   0, '[Personal] fx-5'),  -- other currency: excluded
  (2, 'fx-6', 'Lyft', 'local',  '2026-10-09', 'USD', 1550, 300, '[Personal] fx-6'),  -- counts (Oct): 18.50
  (2, 'fx-7', 'Uber', 'local',  '2026-10-11', 'USD', 9999,   0, '[Personal] fx-7');  -- after asOf: ignored
-- No business-ride case: the API rejects any ride whose subject lacks the personal tag,
-- so such a ride can never be stored (fx-4 was retired for that reason).
INSERT INTO rental (tenant_id, company, confirmation_no, start_date, end_date, purpose, region, currency, gross_cents)
VALUES (2, 'Avis', 'FX-R1', '2026-09-29', '2026-10-02', 'personal', 'local', 'USD', 10000);
INSERT INTO rental_adjustment (rental_pk, line_no, kind, amount_cents, affects_net, effective_date, hold_released)
SELECT rental_pk, 1, 'rebate',        2000, 1, '2026-10-05', NULL FROM rental WHERE tenant_id = 2 AND confirmation_no = 'FX-R1';
INSERT INTO rental_adjustment (rental_pk, line_no, kind, amount_cents, affects_net, effective_date, hold_released)
SELECT rental_pk, 2, 'reimbursement',  500, 0, '2026-10-05', NULL FROM rental WHERE tenant_id = 2 AND confirmation_no = 'FX-R1';
INSERT INTO rental_adjustment (rental_pk, line_no, kind, amount_cents, affects_net, effective_date, hold_released)
SELECT rental_pk, 3, 'hold',          5000, 0, '2026-09-29', 1    FROM rental WHERE tenant_id = 2 AND confirmation_no = 'FX-R1';
-- Tenant 3: half-even tie. 75 cents/month for 1 day of a 30-day month = 2.5 cents -> 2 (half-up would give 3)
INSERT INTO tenant (tenant_id, name) VALUES (3, 'Tie');
INSERT INTO tenant_settings (tenant_id, comparison_start, home_region) VALUES (3, '2026-09-30', 'Testville');
INSERT INTO recurring_cost (tenant_id, name, side, period, amount_cents, effective_from) VALUES
  (3, 'Tiny', 'baseline', 'monthly', 75, '2026-09-01'),
  (3, 'Tiny2','current',  'monthly', 45, '2026-09-01');
