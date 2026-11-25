import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

// UI contract tests use deterministic HTTP fixtures. Real transaction/risk/settlement behavior is separately
// exercised by PaperApprovalWorkflowIT against PostgreSQL in this same completion workflow.
const ORDER = '11111111-1111-4111-8111-111111111111';
const APPROVAL = '22222222-2222-4222-8222-222222222222';
const POLICY = '33333333-3333-4333-8333-333333333333';

async function fixture(page, { blocked = false, expired = false, failOnce = false } = {}) {
  const now = Date.now();
  const approval = {
    id: APPROVAL, orderId: ORDER, policyId: POLICY, policyVersion: 7, state: 'PENDING',
    requestedAt: new Date(now - 10_000).toISOString(),
    expiresAt: new Date(now + (expired ? -1_000 : 300_000)).toISOString(),
    rowVersion: 4, riskDecisionId: '44444444-4444-4444-8444-444444444444',
    requestSnapshot: JSON.stringify({ symbol: 'TCS', side: 'BUY', quantity: 1, notional: 100,
      approvalMode: 'MANUAL', productType: 'DELIVERY', orderType: 'MARKET', exchange: 'NSE' }),
  };
  const decisions = [];
  let attempts = 0;
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, '');
    const json = (body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/system/execution-mode') return json({ activeMode: 'PAPER', liveAvailable: false, liveBlockers: [] });
    if (path === '/approvals' && request.method() === 'GET') {
      const items = url.searchParams.get('state') === approval.state ? [approval] : [];
      return json({ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 });
    }
    if (path === `/approvals/${APPROVAL}/approve`) {
      attempts++;
      if (failOnce && attempts === 1) return json({ message: 'Temporary decision failure' }, 503);
      decisions.push(request.postDataJSON());
      approval.state = 'APPROVED';
      approval.rowVersion++;
      approval.decidedBy = 'operator-ui';
      approval.decisionReason = 'Reviewed';
      return json(approval);
    }
    if (path === `/paper-orders/${ORDER}`) return json({ order: {
      id: ORDER, status: blocked ? 'FAILED' : 'FILLED', rejectionReason: blocked ? 'EMERGENCY_STOP_ACTIVE' : null,
    } });
    return json({});
  });
  return { decisions, attempts: () => attempts };
}

async function connect(page, experience, width) {
  await page.setViewportSize({ width, height: 900 });
  await page.goto('http://127.0.0.1:4173/approvals');
  await page.getByLabel('Appearance — Experience').selectOption(experience);
  await page.getByLabel('Operator API key', { exact: true }).fill('approval-browser-test-key');
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Execution approvals' })).toBeVisible();
  await expect(page.getByText('TCS', { exact: true })).toBeVisible();
}

async function accessible(page) {
  const results = await new AxeBuilder({ page }).analyze();
  const severe = results.violations.filter(item => ['serious', 'critical'].includes(item.impact));
  expect(severe, severe.map(item => `${item.id}: ${item.help}`).join('\n')).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
}

for (const [experience, width] of [['pro-terminal', 1440], ['clear-invest', 390]]) {
  test(`${experience}: explicit confirmation, cancellation and persisted fill outcome`, async ({ page }, testInfo) => {
    const state = await fixture(page);
    await connect(page, experience, width);
    await accessible(page);
    await page.getByRole('button', { name: 'Approve', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    expect(state.decisions).toHaveLength(0);
    await accessible(page);
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(dialog).not.toBeVisible();
    expect(state.decisions).toHaveLength(0);
    await page.getByRole('button', { name: 'Approve', exact: true }).click();
    await dialog.getByLabel('Decision reason').fill('Reviewed');
    await dialog.getByRole('button', { name: 'Confirm approve', exact: true }).click();
    await expect(page.getByText('PAPER order filled', { exact: true })).toBeVisible();
    expect(state.decisions).toEqual([{ expectedRowVersion: 4, actor: 'operator-ui', reason: 'Reviewed' }]);
    await expect(page.getByText('No pending approvals', { exact: true })).toBeVisible();
    await accessible(page);
    await page.screenshot({ path: testInfo.outputPath(`p7-${experience}-${width}.png`), fullPage: true });
  });

  test(`${experience}: approval success cannot disguise a blocked execution`, async ({ page }) => {
    await fixture(page, { blocked: true });
    await connect(page, experience, width);
    await page.getByRole('button', { name: 'Approve', exact: true }).click();
    await page.getByRole('button', { name: 'Confirm approve', exact: true }).click();
    await expect(page.getByText('Approval recorded; execution blocked', { exact: true })).toBeVisible();
    await expect(page.getByText('EMERGENCY_STOP_ACTIVE', { exact: true })).toBeVisible();
    await expect(page.getByText('PAPER order filled', { exact: true })).toHaveCount(0);
  });
}

test('expired cached approval cannot open a new decision confirmation', async ({ page }) => {
  const state = await fixture(page, { expired: true });
  await connect(page, 'clear-invest', 390);
  await expect(page.getByRole('button', { name: 'Approve', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Reject', exact: true })).toBeDisabled();
  expect(state.attempts()).toBe(0);
});

test('recoverable decision error keeps the request version and allows retry', async ({ page }) => {
  const state = await fixture(page, { failOnce: true });
  await connect(page, 'pro-terminal', 1440);
  await page.getByRole('button', { name: 'Approve', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm approve', exact: true }).click();
  await expect(page.getByText('Decision failed', { exact: true })).toBeVisible();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('button', { name: 'Confirm approve', exact: true }).click();
  await expect(page.getByText('PAPER order filled', { exact: true })).toBeVisible();
  expect(state.attempts()).toBe(2);
  expect(state.decisions).toHaveLength(1);
  expect(state.decisions[0].expectedRowVersion).toBe(4);
});

test('presentation change preserves the open approval review and requires the same final confirmation', async ({ page }) => {
  const state = await fixture(page);
  await connect(page, 'pro-terminal', 1440);
  await page.getByRole('button', { name: 'Approve', exact: true }).click();
  await page.getByRole('dialog').getByLabel('Decision reason').fill('Reviewed immutable request');
  // Programmatic preference change tests context updates while the dialog keeps background controls inert.
  await page.getByLabel('Appearance — Experience').selectOption('clear-invest', { force: true });
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('dialog').getByLabel('Decision reason')).toHaveValue('Reviewed immutable request');
  expect(state.decisions).toHaveLength(0);
  await page.getByRole('dialog').getByRole('button', { name: 'Confirm approve', exact: true }).click();
  await expect(page.getByText('PAPER order filled', { exact: true })).toBeVisible();
  expect(state.decisions).toEqual([{ expectedRowVersion: 4, actor: 'operator-ui', reason: 'Reviewed immutable request' }]);
});
