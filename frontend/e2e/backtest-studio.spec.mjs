import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const runId = '11111111-1111-4111-8111-111111111111';
const validationId = '22222222-2222-4222-8222-222222222222';
const walkId = '33333333-3333-4333-8333-333333333333';

function runSummary() {
  return {
    id: runId, instrumentId: 101, exchange: 'NSE', tradingSymbol: 'RELIANCE', symbolToken: '2885',
    interval: 'ONE_DAY', fromDate: '2026-01-01', toDate: '2026-06-30', strategyVersion: 'SMA_TREND_VOLUME_V1',
    initialCapital: 80000, candlesEvaluated: 120, tradeCount: 12, winningTrades: 7, losingTrades: 5,
    winRatePercent: 58.33, grossPnl: 11200, totalFees: 700, netPnl: 10500, finalEquity: 90500,
    totalReturnPercent: 13.125, maxDrawdownPercent: 6.4, profitFactor: 1.72, feeRate: 0.0005,
    slippageRate: 0.0008, createdAt: '2026-07-01T09:00:00Z',
  };
}

const trades = [{
  tradeNumber: 1, entryAt: '2026-02-01T03:45:00Z', exitAt: '2026-02-05T03:45:00Z', entryPrice: 2400,
  exitPrice: 2460, quantity: 10, stopLoss: 2360, targetPrice: 2480, grossPnl: 600, entryFee: 20,
  exitFee: 20, netPnl: 560, exitReason: 'TARGET',
}];

const equity = [
  { pointTime: '2026-01-01T03:45:00Z', equity: 80000, cash: 80000, openValue: 0 },
  { pointTime: '2026-03-01T03:45:00Z', equity: 84400, cash: 84400, openValue: 0 },
  { pointTime: '2026-06-30T03:45:00Z', equity: 90500, cash: 90500, openValue: 0 },
];

const validation = {
  id: validationId, tradingSymbol: 'RELIANCE', interval: 'ONE_DAY', fromDate: '2026-01-01', toDate: '2026-06-30',
  splitDate: '2026-05-08', outOfSamplePercent: 30, trainingReturnPercent: 11.2, outOfSampleReturnPercent: 8.6,
  returnDeltaPercentagePoints: -2.6, trainingCandles: 84, outOfSampleCandles: 36, createdAt: '2026-07-01T09:10:00Z',
};

async function mockBacktestApi(page) {
  let created = false;
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, '');
    const method = request.method();
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

    if (path === '/system/execution-mode') return json({ mode: 'PAPER', liveAvailable: false });
    if (path === '/instruments/search') return json({ content: [{ id: 101, exchange: 'NSE', tradingSymbol: 'RELIANCE', symbolToken: '2885', name: 'Reliance Industries', instrumentType: 'EQ' }], page: 0, size: 12, totalElements: 1, totalPages: 1 });
    if (path === '/backtests' && method === 'POST') { created = true; return json({ run: runSummary(), trades }); }
    if (path === '/backtests' && method === 'GET') return json({ items: created ? [runSummary()] : [], page: 0, size: 20, totalElements: created ? 1 : 0, totalPages: created ? 1 : 0 });
    if (path === `/backtests/${runId}`) return json({ run: runSummary(), trades });
    if (path === `/backtests/${runId}/equity`) return json(equity);
    if (path === `/backtests/${runId}/export.csv`) return route.fulfill({ status: 200, contentType: 'text/csv', headers: { 'Content-Disposition': `attachment; filename=backtest-${runId}.csv` }, body: 'runId,tradeNumber\n' });
    if (path === '/backtests/walk-forward' && method === 'POST') return json({ run: { id: walkId, status: 'COMPLETED' }, folds: [{ validationReturnPercent: 4.1 }, { validationReturnPercent: 2.2 }, { validationReturnPercent: -0.8 }] });
    if (path === '/backtests/validation' && method === 'POST') return json({ validation, training: { run: runSummary(), trades: [] }, outOfSample: { run: runSummary(), trades: [] } });
    if (path === '/backtests/validation' && method === 'GET') return json([]);
    if (path === `/backtests/validation/${validationId}`) return json({ validation, training: { run: runSummary(), trades: [] }, outOfSample: { run: runSummary(), trades: [] } });
    return json({});
  });
}

async function unlockAndRun(page, experience) {
  await page.goto('http://127.0.0.1:4173/backtesting');
  await page.getByLabel('Appearance — Experience').selectOption(experience);
  await page.getByLabel('Operator API key').fill('e2e-test-key');
  await page.getByRole('button', { name: 'Connect' }).click();
  await expect(page.getByText('Operator key loaded')).toBeVisible();

  await page.getByLabel('NSE cash-equity instrument').fill('RELIANCE');
  await page.getByRole('button', { name: /RELIANCE/ }).click();
  await page.getByRole('button', { name: 'Run backtest', exact: true }).click();
  await expect(page.getByRole('img', { name: 'Backtest equity curve' })).toBeVisible();
  await expect(page.getByText('TARGET')).toBeVisible();
  await page.getByRole('button', { name: 'Run walk-forward' }).click();
  await expect(page.getByText('2/3')).toBeVisible();
  await page.getByRole('button', { name: 'Run 30% holdout' }).click();
  await expect(page.getByText('Training return')).toBeVisible();
}

async function assertAccessibility(page) {
  const results = await new AxeBuilder({ page }).analyze();
  const severe = results.violations.filter((item) => ['serious', 'critical'].includes(item.impact));
  expect(severe, severe.map((item) => `${item.id}: ${item.help}`).join('\n')).toEqual([]);
}

for (const scenario of [
  { name: 'Pro Terminal desktop', experience: 'pro-terminal', width: 1440, height: 900 },
  { name: 'Clear Invest mobile', experience: 'clear-invest', width: 390, height: 844 },
]) {
  test(`E2E-P3 config→run→result→compare — ${scenario.name}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: scenario.width, height: scenario.height });
    await mockBacktestApi(page);
    await unlockAndRun(page, scenario.experience);
    await expect(page.getByRole('heading', { name: 'Backtest Studio' })).toBeVisible();
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', scenario.width);
    await assertAccessibility(page);
    await page.screenshot({ path: testInfo.outputPath(`${scenario.experience}-${scenario.width}.png`), fullPage: true });
  });
}
