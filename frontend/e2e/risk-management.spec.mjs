import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const decisionId = '55555555-5555-4555-8555-555555555555';
const orderId = '66666666-6666-4666-8666-666666666666';

function policy(version = 3) {
  return {
    id: '44444444-4444-4444-8444-444444444444',
    version,
    policyName: `PAPER safety v${version}`,
    active: true,
    snapshot: {
      id: '44444444-4444-4444-8444-444444444444',
      version,
      riskPerTradePercent: 0.5,
      maxDailyDrawdownPercent: 2,
      maxOpenPositions: 5,
      minRewardRiskRatio: 1.5,
      maxOrderNotional: 25000,
      maxGrossExposurePercent: 60,
      maxSymbolExposurePercent: 20,
      maxTradesPerDay: 12,
      signalMaxAgeMinutes: 15,
      marketStateMaxAgeSeconds: 120,
      enforceMarketSession: true,
      rejectExistingSymbolPosition: true,
      rejectDuplicatePendingExposure: true,
      feeRate: 0.0005,
      slippageRate: 0.0008,
      feeSlippageProfileVersion: 'paper-v1',
    },
    effectiveFrom: '2026-10-29T10:00:00Z',
    effectiveTo: null,
    createdBy: 'operator',
    createdAt: '2026-10-29T10:00:00Z',
  };
}

function decisionPage() {
  return {
    items: [{
      id: decisionId,
      orderId,
      evaluatedAt: '2026-10-29T11:00:00Z',
      decision: 'REJECTED',
      resultClass: 'REJECTED',
      estimatedRisk: 2200,
      riskBudget: 400,
      availableCash: 72000,
      openPositions: 2,
      reasonCodes: ['RISK_BUDGET_EXCEEDED'],
    }],
    page: 0,
    size: 10,
    totalItems: 1,
    totalPages: 1,
  };
}

async function mockRiskApi(page) {
  let activePolicy = policy();
  let halted = false;
  let createdPolicyBody = null;

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, '');
    const method = request.method();
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

    if (path === '/system/execution-mode') return json({ activeMode: 'PAPER', liveAvailable: false, liveBlockers: [] });
    if (path === '/dashboard/summary') {
      return json({
        mode: 'PAPER', liveTradingEnabled: false, newOrdersHalted: halted,
        capital: 80000, dailyPnl: -240, exposurePercent: 18.5,
        cashReconciled: true, asOf: '2026-10-29T11:05:00Z',
      });
    }
    if (path === '/risk/policy' && method === 'GET') return json(activePolicy);
    if (path === '/risk/decisions' && method === 'GET') return json(decisionPage());
    if (path === '/risk/emergency-stop' && method === 'PUT') {
      const body = request.postDataJSON();
      if (body.halted && (!body.reason || body.reason.trim().length < 3)) {
        return json({ detail: 'A halt reason of at least 3 characters is required' }, 400);
      }
      halted = !!body.halted;
      return json({ newOrdersHalted: halted, reason: body.reason || 'Operator resumed trading' });
    }
    if (path === '/risk/policies' && method === 'POST') {
      createdPolicyBody = request.postDataJSON();
      activePolicy = {
        ...policy(activePolicy.version + 1),
        policyName: createdPolicyBody.policyName,
        snapshot: { ...policy(activePolicy.version + 1).snapshot, ...createdPolicyBody, version: activePolicy.version + 1 },
      };
      return json(activePolicy, 200);
    }
    return json({});
  });

  return {
    createdPolicyBody: () => createdPolicyBody,
    halted: () => halted,
  };
}

async function connect(page, experience, viewport) {
  await page.setViewportSize(viewport);
  await page.goto('http://127.0.0.1:4173/risk');
  await page.getByLabel('Appearance — Experience').selectOption(experience);
  await page.getByLabel('Operator API key', { exact: true }).fill('e2e-test-key');
  await page.getByRole('button', { name: 'Connect' }).click();
  await expect(page.locator('#api-key')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Risk management' })).toBeVisible();
}

async function assertAccessibility(page) {
  const results = await new AxeBuilder({ page }).analyze();
  const severe = results.violations.filter((item) => ['serious', 'critical'].includes(item.impact));
  expect(severe, severe.map((item) => `${item.id}: ${item.help}`).join('\n')).toEqual([]);
}

test('E2E-P5 Pro Terminal — policy diff/activation and emergency stop safety', async ({ page }, testInfo) => {
  const state = await mockRiskApi(page);
  await connect(page, 'pro-terminal', { width: 1440, height: 900 });

  await expect(page.getByText('Hard risk limits')).toBeVisible();
  await expect(page.getByText('Risk decisions')).toBeVisible();
  await expect(page.getByText('v3', { exact: true })).toBeVisible();
  await expect(page.getByText('RISK_BUDGET_EXCEEDED')).toBeVisible();

  await page.getByRole('button', { name: 'Create new policy version' }).click();
  await expect(page.getByText('Draft values are cloned from active policy v3.')).toBeVisible();
  await page.getByLabel('Risk per trade (%)').fill('0.75');
  await page.getByRole('button', { name: 'Review changes' }).click();
  await expect(page.getByText('Immutable version activation')).toBeVisible();
  await expect(page.getByText('0.75%')).toBeVisible();
  await page.getByRole('button', { name: 'Activate new version' }).click();
  await expect.poll(() => state.createdPolicyBody()?.riskPerTradePercent).toBe(0.75);
  await expect(page.getByText('v4', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'Halt new orders' }).click();
  const confirm = page.getByRole('button', { name: 'Confirm' });
  await expect(confirm).toBeDisabled();
  await page.getByLabel('Reason (required, 3–200 characters)').fill('Ops');
  await expect(confirm).toBeEnabled();
  await confirm.click();
  await expect.poll(() => state.halted()).toBe(true);

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  await assertAccessibility(page);
  await page.screenshot({ path: testInfo.outputPath('pro-terminal-risk-1440.png'), fullPage: true });
});

test('E2E-P5 Clear Invest mobile — same risk data with guided composition', async ({ page }, testInfo) => {
  await mockRiskApi(page);
  await connect(page, 'clear-invest', { width: 390, height: 844 });

  await expect(page.getByText('Your risk health')).toBeVisible();
  await expect(page.getByText('Risk controls healthy')).toBeVisible();
  await expect(page.getByText('What the system will not exceed')).toBeVisible();
  await expect(page.getByText('Why trades were accepted or blocked')).toBeVisible();
  await expect(page.getByText('RISK_BUDGET_EXCEEDED')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Create new policy version' })).toBeVisible();

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  await assertAccessibility(page);
  await page.screenshot({ path: testInfo.outputPath('clear-invest-risk-390.png'), fullPage: true });
});
