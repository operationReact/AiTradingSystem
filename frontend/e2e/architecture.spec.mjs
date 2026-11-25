import { test, expect } from '@playwright/test';
import { candidate, evaluation, scenario } from './fixtures/candidate.mjs';
import AxeBuilder from '@axe-core/playwright';

// Deterministic HTTP contracts; real PostgreSQL source identity and operator APIs run in backend CI.
async function fixture(page) {
  const requests = [];
  let acknowledged = false;
  await page.route('**/api/**', async route => {
    const r = route.request();
    const url = new URL(r.url());
    requests.push({ path: url.pathname, method: r.method(), key: r.headers()['x-api-key'] });
    const reply = body => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    if (url.pathname.endsWith('/system/execution-mode')) return reply({ activeMode: 'PAPER', liveAvailable: false });
    if (url.pathname.endsWith('/operations/health')) return reply({ state: 'DEGRADED', executionMode: 'PAPER', emergencyStopActive: true, checkedAt: '2026-10-30T05:00:00Z', components: [{ component: 'reconciliation', state: 'HEALTHY', reasonCode: 'LAST_RUN_CLEAN', asOf: '2026-10-30T04:59:00Z', ageSeconds: 60 }, { component: 'angelOne', state: 'UNKNOWN', reasonCode: 'NOT_CONFIGURED' }] });
    if (url.pathname.endsWith('/notifications/n1/acknowledge')) { acknowledged = true; return reply({ id: 'n1', acknowledgedBy: 'operator' }); }
    if (url.pathname.endsWith('/notifications')) return reply({ items: acknowledged ? [] : [{ id: 'n1', severity: 'CRITICAL', title: 'Emergency stop active', message: 'Operator review required', type: 'RISK_EMERGENCY_STOP', entityType: 'TRADING_CONTROL', entityId: '1', createdAt: '2026-10-30T05:00:00Z' }], totalPages: 1, projection: { backlogRemaining: false } });
    if (url.pathname.endsWith('/audit/export')) return route.fulfill({ contentType: 'text/csv', body: '"id","actor"\r\n"1","operator"\r\n' });
    if (url.pathname.endsWith('/audit')) return reply({ items: [{ id: 1, eventType: 'ORDER_REJECTED', actor: 'operator', actorSource: 'AUTHENTICATED', entityType: 'PAPER_ORDER', entityId: 'order-1', details: { reason: '<script>unsafe()</script>' }, createdAt: '2026-10-30T05:00:00Z' }], totalPages: 1 });
    if (url.pathname.endsWith('/research/candidates/42')) return reply(candidate);
    if (url.pathname.endsWith('/research/simulations/scenario-1')) return reply(scenario);
    if (url.pathname.endsWith('/research/candidates/42/evaluations')) return reply([evaluation]);
    return reply({});
  });
  return requests;
}
async function connect(page, path, experience, width) {
  await page.setViewportSize({ width, height: 900 });
  await page.goto(`http://127.0.0.1:4173${path}`);
  await page.getByLabel('Appearance — Experience').selectOption(experience);
  await page.getByLabel('Operator API key', { exact: true }).fill('architecture-test-key');
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
}
for (const [experience, width] of [['pro-terminal', 1440], ['clear-invest', 390]]) {
  test(`${experience}: audit form, route, authenticated query and export survive presentation switch`, async ({ page }) => {
    const requests = await fixture(page);
    await connect(page, '/audit', experience, width);
    await expect(page.getByText('ORDER_REJECTED', { exact: true })).toBeVisible();
    await page.getByLabel('Event type', { exact: true }).fill('APPROVAL_APPROVED');
    await page.getByLabel('Appearance — Experience').selectOption(experience === 'pro-terminal' ? 'clear-invest' : 'pro-terminal');
    await expect(page.getByLabel('Event type', { exact: true })).toHaveValue('APPROVAL_APPROVED');
    await expect(page).toHaveURL(/\/audit$/);
    await page.getByText('Recorded payload', { exact: true }).click();
    await expect(page.getByText(/<script>unsafe/)).toBeVisible();
    expect(requests.filter(r => r.path === '/api/audit')).toHaveLength(1);
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Export CSV' }).click();
    expect((await download).suggestedFilename()).toBe('audit.csv');
    expect(requests.filter(r => r.path.startsWith('/api/audit')).every(r => r.key === 'architecture-test-key')).toBe(true);
    const axe = await new AxeBuilder({ page }).analyze();
    expect(axe.violations.filter(v => ['serious', 'critical'].includes(v.impact))).toEqual([]);
  });
  test(`${experience}: notifications acknowledge through the controller and operations retain authoritative state`, async ({ page }) => {
    const requests = await fixture(page);
    await connect(page, '/notifications', experience, width);
    await expect(page.getByText('Emergency stop active', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Acknowledge', exact: true }).click();
    await expect(page.getByText('Notification acknowledged', { exact: true })).toBeVisible();
    await expect(page.getByText('No notifications in this view')).toBeVisible();
    expect(requests.filter(r => r.method === 'POST')).toHaveLength(1);
    if (width < 768) await page.getByRole('button', { name: 'Open navigation' }).click();
    await page.getByRole('link', { name: 'Operations', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Operations health' })).toBeVisible();
    await expect(page.getByText('LAST_RUN_CLEAN', { exact: true })).toBeVisible();
    await expect(page.getByText('UNKNOWN', { exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
  });
  test(`${experience}: candidate deep link renders persisted research identity without order actions`, async ({ page }) => {
    await fixture(page);
    await connect(page, '/research/candidates/42', experience, width);
    await expect(page.getByRole('heading', { name: 'Candidate 42' })).toBeVisible();
    await expect(page.getByText('Research only — not an order', { exact: true })).toBeVisible();
    await expect(page.getByText('scenario-1', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /approve|submit order/i })).toHaveCount(0);
  });
}
