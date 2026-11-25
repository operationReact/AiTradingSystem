import { appendFileSync } from 'node:fs';
import { chromium } from 'playwright';

const baseUrl = process.env.WEB_RUNTIME_URL ?? 'http://127.0.0.1:4173';
const apiKey = process.env.CONNECTED_API_KEY;
const stage = process.env.WEB_RUNTIME_STAGE ?? 'full';
if (!apiKey) throw new Error('CONNECTED_API_KEY is required');

const browser = await chromium.launch({
  headless: true,
  args: ['--lang=en-US', '--force-renderer-accessibility'],
});
const context = await browser.newContext({
  viewport: { width: 1440, height: 900 },
  locale: 'en-US',
});
const page = await context.newPage();
const browserDiagnostics = [];
page.on('console', (message) => {
  const line = `console.${message.type()}: ${message.text()}`;
  browserDiagnostics.push(line);
  console.log(line);
});
page.on('pageerror', (error) => {
  const line = `pageerror: ${error.stack ?? error.message}`;
  browserDiagnostics.push(line);
  console.error(line);
});
page.on('requestfailed', (request) => {
  const line = `requestfailed: ${request.method()} ${request.url()} ${request.failure()?.errorText ?? ''}`;
  browserDiagnostics.push(line);
  console.error(line);
});

async function enableSemantics() {
  const glassPane = page.locator('flt-glass-pane');
  await glassPane.waitFor({ state: 'attached', timeout: 30000 });
  const placeholder = page.locator('flt-semantics-placeholder').first();
  if (await placeholder.count()) {
    await placeholder.evaluate((element) => element.click());
  }
  await page.locator('flt-semantics').first().waitFor({ state: 'attached', timeout: 10000 }).catch(() => {});
}

const exactText = (text) => page.getByText(text, { exact: true }).first();
const metricSelector = (slug) => `flt-semantics[flt-semantics-identifier="health-metric-${slug}"]`;

async function waitForMetric(slug, expectedText, timeout = 15000) {
  const selector = metricSelector(slug);
  await page.locator(selector).first().waitFor({ state: 'attached', timeout });
  await page.waitForFunction(
    ({ selector: metricNodeSelector, expected }) => {
      const node = document.querySelector(metricNodeSelector);
      return (node?.textContent ?? '').trim() === expected;
    },
    { selector, expected: expectedText },
    { timeout },
  );
}

async function clickSemantic(name) {
  const semanticTarget = page.locator(
    `flt-semantics[aria-label="${name.replaceAll('"', '\\"')}"][flt-tappable]`,
  ).first();
  try {
    await semanticTarget.waitFor({ state: 'attached', timeout: 5000 });
    await semanticTarget.click();
    return;
  } catch (_) {
    // Fall through to role/text locators for controls represented differently.
  }

  for (const role of ['button', 'menuitem']) {
    const target = page.getByRole(role, { name, exact: true }).first();
    try {
      await target.waitFor({ state: 'attached', timeout: 3000 });
      await target.click();
      return;
    } catch (_) {
      // Try the next representation.
    }
  }
  await exactText(name).click();
}

async function enterOperatorKey(value) {
  const textbox = page.getByRole('textbox', { name: 'Operator key', exact: true });
  const target = (await textbox.count()) ? textbox.first() : page.getByLabel('Operator key').first();
  await target.click();
  await page.keyboard.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A');
  await page.keyboard.press('Backspace');
  await page.keyboard.type(value);
}

async function credentialLeaksOutsideOperatorField(secret) {
  return page.evaluate((value) => {
    const candidates = document.querySelectorAll('flt-semantics, input, textarea, [role]');
    return [...candidates].some((element) => {
      const role = element.getAttribute('role');
      const label = element.getAttribute('aria-label') ?? '';
      const isOperatorField =
        (role === 'textbox' || element.tagName === 'INPUT' || element.tagName === 'TEXTAREA') &&
        label.includes('Operator key');
      if (isOperatorField) return false;

      const exposed = [
        element.textContent ?? '',
        element.getAttribute('aria-label') ?? '',
        element.getAttribute('aria-valuetext') ?? '',
        element.getAttribute('title') ?? '',
      ].join('\n');
      return exposed.includes(value);
    });
  }, secret);
}

async function openApp() {
  const healthUrl = `${baseUrl.replace(/\/$/, '')}/#/operations/health`;
  const response = await page.goto(healthUrl, { waitUntil: 'domcontentloaded' });
  if (!response?.ok()) throw new Error(`Flutter web health route returned HTTP ${response?.status()}`);
  await enableSemantics();
  await exactText('Operations health').waitFor({ timeout: 30000 });
}

async function connectWith(key) {
  await enterOperatorKey(key);
  await clickSemantic('Connect and refresh');
}

async function provePositiveHealth() {
  await connectWith(apiKey);
  await waitForMetric('execution', 'Execution PAPER');
  await waitForMetric('live', 'LIVE UNAVAILABLE');
}

async function proveAppearanceContinuity() {
  await provePositiveHealth();
  await clickSemantic('Clean');
  await waitForMetric('execution', 'Execution PAPER');
  await clickSemantic('System theme');
  await page.locator('flt-semantics[role="menuitem"][aria-label="Dark"]').first()
    .waitFor({ state: 'attached', timeout: 10000 });
  await clickSemantic('Dark');
  await waitForMetric('execution', 'Execution PAPER');
}

async function proveNegativeAuth() {
  const badKey = `${apiKey}wrong`;
  await connectWith(badKey);
  const authError = page.locator(
    'flt-semantics[role="group"][aria-label*="AUTHENTICATION_REJECTED"]',
  ).first();
  await authError.waitFor({ state: 'attached', timeout: 15000 });
  if (await credentialLeaksOutsideOperatorField(badKey)) {
    throw new Error('credential text leaked outside the operator credential field');
  }
}

async function collectSemanticsDiagnostics() {
  return page.locator('flt-semantics').evaluateAll((elements) =>
    elements.map((element, index) => ({
      index,
      id: element.id || null,
      identifier: element.getAttribute('flt-semantics-identifier'),
      label: element.getAttribute('aria-label'),
      role: element.getAttribute('role'),
      live: element.getAttribute('aria-live'),
      valueText: element.getAttribute('aria-valuetext'),
      text: (element.textContent ?? '').trim().slice(0, 200),
      outerHTML: element.outerHTML.slice(0, 700),
    })),
  ).catch(() => []);
}

try {
  await openApp();
  if (stage === 'startup') {
    // Startup/semantics contract only.
  } else if (stage === 'positive') {
    await provePositiveHealth();
  } else if (stage === 'appearance') {
    await proveAppearanceContinuity();
  } else if (stage === 'negative') {
    await proveNegativeAuth();
  } else if (stage === 'full') {
    await proveAppearanceContinuity();
    await clickSemantic('Clear session');
    await proveNegativeAuth();
  } else {
    throw new Error(`Unknown WEB_RUNTIME_STAGE: ${stage}`);
  }

  await page.screenshot({ path: `/tmp/fc03-flutter-web-runtime-${stage}.png`, fullPage: true });
} catch (error) {
  const title = await page.title().catch(() => '<unavailable>');
  const html = (await page.content().catch(() => '<unavailable>')).slice(0, 4000);
  const semantics = await collectSemanticsDiagnostics();
  const detail = [
    `## FC-03 Flutter Web runtime failure — ${stage}`,
    '',
    '```text',
    error?.stack ?? String(error),
    '',
    `document title: ${title}`,
    '',
    'CanvasKit semantics nodes:',
    JSON.stringify(semantics, null, 2) || '<none>',
    '',
    'browser diagnostics:',
    browserDiagnostics.join('\n') || '<none>',
    '',
    'document html (first 4000 chars):',
    html,
    '```',
    '',
  ].join('\n');
  console.error(detail);
  if (process.env.GITHUB_STEP_SUMMARY) {
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, detail, 'utf8');
  }
  await page.screenshot({ path: `/tmp/fc03-flutter-web-runtime-${stage}-failure.png`, fullPage: true }).catch(() => {});
  throw error;
} finally {
  await context.close();
  await browser.close();
}
