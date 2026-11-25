import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const NOW = '2026-10-29T12:00:00Z';

function portfolio() {
  return {
    mode: 'PAPER',
    equity: 101250.50,
    cash: 74250.50,
    invested: 25000,
    marketValue: 27000,
    realizedPnl: 750.50,
    unrealizedPnl: 2000,
    exposurePercent: 26.67,
    openPositions: 1,
    markPriceSource: 'LATEST_PERSISTED_CANDLE',
    cashReconciled: true,
    asOf: NOW,
  };
}

const positions = [{
  instrumentId: 101,
  exchange: 'NSE',
  symbol: 'TCS',
  productType: 'DELIVERY',
  quantity: 10,
  averageCost: 2500,
  markPrice: 2700,
  marketValue: 27000,
  unrealizedPnl: 2000,
}];

const fills = [{
  id: '11111111-1111-4111-8111-111111111111',
  orderId: '22222222-2222-4222-8222-222222222222',
  fillSequence: 1,
  symbol: 'TCS',
  side: 'BUY',
  productType: 'DELIVERY',
  quantity: 10,
  fillPrice: 2500,
  feeAmount: 12.5,
  realizedPnl: 0,
  filledAt: '2026-10-29T11:30:00Z',
}];

const cashLedger = [{
  id: '33333333-3333-4333-8333-333333333333',
  orderId: '22222222-2222-4222-8222-222222222222',
  entryType: 'BUY_SETTLEMENT',
  amount: -25012.5,
  balanceAfter: 74250.5,
  symbol: 'TCS',
  productType: 'DELIVERY',
  createdAt: '2026-10-29T11:30:00Z',
}];

function cleanRun(id = '44444444-4444-4444-8444-444444444444') {
  return {
    id,
    runId: id,
    status: 'CLEAN',
    differenceCount: 0,
    checksPerformed: 8,
    startedAt: '2026-10-29T11:45:00Z',
    completedAt: '2026-10-29T11:45:01Z',
  };
}

async function mockPaperApi(page) {
  const runs = [cleanRun()];
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, '');
    const method = request.method();
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

    if (path === '/system/execution-mode') return json({ activeMode: 'PAPER', liveAvailable: false, liveBlockers: [] });
    if (path === '/paper-portfolio') return json(portfolio());
    if (path === '/paper-positions') return json(positions);
    if (path.startsWith('/paper-fills')) return json(fills);
    if (path.startsWith('/paper-cash-ledger')) return json(cashLedger);
    if (path.startsWith('/paper-reconciliation/runs')) return json(runs);
    if (path === '/paper-reconciliation' && method === 'POST') {
      runs.unshift(cleanRun('55555555-5555-4555-8555-555555555555'));
      return json(runs[0]);
    }
    return json({});
  });
  return { runs };
}

async function connect(page, experience, viewport) {
  await page.setViewportSize(viewport);
  await page.goto('http://127.0.0.1:4173/portfolio');
  await page.getByLabel('Appearance — Experience').selectOption(experience);
  await page.getByLabel('Operator API key', { exact: true }).fill('e2e-test-key');
  await page.getByRole('button', { name: 'Connect' }).click();
  await expect(page.locator('#api-key')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Paper portfolio' })).toBeVisible();
}

async function assertAccessibility(page) {
  const results = await new AxeBuilder({ page }).analyze();
  const severe = results.violations.filter((item) => ['serious', 'critical'].includes(item.impact));
  expect(severe, severe.map((item) => `${item.id}: ${item.help}`).join('\n')).toEqual([]);
}

async function assertCanonicalPortfolioEvidence(page) {
  await expect(page.getByText('Paper ledger reconciled')).toBeVisible();
  await expect(page.getByText('Gross exposure')).toBeVisible();
  await expect(page.getByText('TCS', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('BUY_SETTLEMENT')).toBeVisible();
  await expect(page.getByText('₹2,000.00').first()).toBeVisible();
  await expect(page.getByText('₹750.50').first()).toBeVisible();
}

test('E2E-P6 Pro Terminal desktop — fills → positions → ledger → reconciliation', async ({ page }, testInfo) => {
  const state = await mockPaperApi(page);
  await connect(page, 'pro-terminal', { width: 1440, height: 900 });
  await assertCanonicalPortfolioEvidence(page);

  await page.getByRole('button', { name: 'Run reconciliation' }).click();
  await expect.poll(() => state.runs.length).toBe(2);
  await expect(page.getByText('Paper ledger reconciled')).toBeVisible();

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  await assertAccessibility(page);
  await page.screenshot({ path: testInfo.outputPath('p6-pro-portfolio-1440.png'), fullPage: true });
});

test('E2E-P6 Clear Invest mobile — same accounting evidence remains readable', async ({ page }, testInfo) => {
  await mockPaperApi(page);
  await connect(page, 'clear-invest', { width: 390, height: 844 });
  await assertCanonicalPortfolioEvidence(page);
  await expect(page.getByRole('link', { name: /Portfolio/ })).toBeVisible();

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  await assertAccessibility(page);
  await page.screenshot({ path: testInfo.outputPath('p6-clear-portfolio-390.png'), fullPage: true });
});

test('portfolio controller preserves accounting evidence and reconciliation authority across a design switch', async ({ page }) => {
  const state = await mockPaperApi(page);
  await connect(page, 'pro-terminal', { width: 1440, height: 900 });
  await assertCanonicalPortfolioEvidence(page);
  await page.getByLabel('Appearance — Experience').selectOption('clear-invest');
  await assertCanonicalPortfolioEvidence(page);
  expect(state.runs).toHaveLength(1);
  await page.getByRole('button', { name: 'Run reconciliation' }).click();
  await expect.poll(() => state.runs.length).toBe(2);
  await page.getByRole('button', { name: 'Clear operator API key', exact: true }).click();
  await expect(page.getByText('Operator key required', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Run reconciliation' })).toHaveCount(0);
});
