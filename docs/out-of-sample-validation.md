# PAPER out-of-sample validation

Out-of-sample validation compares the existing fixed SMA/volume strategy on an earlier training window and a later holdout window. It is designed for research, not to claim predictive performance or recommend trades.

## API

All endpoints require the normal operator API key.

- `POST /api/backtests/validation` creates and persists both independent runs plus a validation record.
- `GET /api/backtests/validation?limit=20` lists recent validation summaries.
- `GET /api/backtests/validation/{id}` returns the training and holdout run details.

Example body:

```json
{
  "instrumentId": 101,
  "interval": "ONE_DAY",
  "fromDate": "2025-01-01",
  "toDate": "2025-12-31",
  "outOfSamplePercent": 30,
  "initialCapital": 80000
}
```

`outOfSamplePercent` defaults to 30 and is limited to 10–50. Date ranges are capped at 366 calendar days and source candles are capped at 20,000. Both training and holdout need sufficient stored candles. All timestamps are interpreted in Asia/Kolkata.

## Split and leakage control

- The later holdout starts at the configured split date.
- The training run uses candles before that date only.
- The holdout calculation may read pre-split candles for indicator warm-up, but the engine is prevented from opening positions before the split timestamp.
- The training and holdout simulations start independently with the same initial capital. No training positions or cash state are carried into the holdout.
- The strategy parameters are fixed from server configuration for both periods; this feature does not perform parameter optimization or machine learning.

The summary reports training return, holdout return, and the difference in percentage points. A result is not evidence of future profitability: market regime shifts, data quality, brokerage schedules, execution constraints, and overfitting remain important limitations.
