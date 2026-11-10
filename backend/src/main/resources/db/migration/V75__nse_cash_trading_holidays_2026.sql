-- Verified against the NSE calendar for 2026 on 2026-11-10.
-- https://www.nseindia.com/resources/exchange-communication-holidays
-- A holiday must not be inferred to be an active session merely because an
-- off-hours SmartAPI FULL quote includes a same-day exchFeedTime.
-- The 2026-11-08 Sunday Muhurat session is handled explicitly by
-- IndianCashMarketCalendar; do NOT mark it as a normal cash trading day.
INSERT INTO market_holiday (trade_date, market, holiday_name, source, source_as_of)
VALUES
  ('2026-01-15', 'NSE_CASH', 'Maharashtra municipal corporation election', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-01-26', 'NSE_CASH', 'Republic Day', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-03-03', 'NSE_CASH', 'Holi', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-03-26', 'NSE_CASH', 'Shri Ram Navami', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-03-31', 'NSE_CASH', 'Shri Mahavir Jayanti', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-04-03', 'NSE_CASH', 'Good Friday', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-04-14', 'NSE_CASH', 'Dr Babasaheb Ambedkar Jayanti', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-05-01', 'NSE_CASH', 'Maharashtra Day', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-05-28', 'NSE_CASH', 'Bakri Id', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-06-26', 'NSE_CASH', 'Muharram', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-09-14', 'NSE_CASH', 'Ganesh Chaturthi', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-10-02', 'NSE_CASH', 'Mahatma Gandhi Jayanti', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-10-20', 'NSE_CASH', 'Dussehra', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-11-10', 'NSE_CASH', 'Diwali Balipratipada', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-11-24', 'NSE_CASH', 'Guru Nanak Jayanti', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10'),
  ('2026-12-25', 'NSE_CASH', 'Christmas', 'NSE_TRADING_HOLIDAYS_2026', '2026-11-10')
ON CONFLICT (trade_date) DO NOTHING;
