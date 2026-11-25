const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const root = 'http://127.0.0.1:8765/';
  const folder = path.resolve(__dirname, '../../apps/trading_client/captured-screens');
  fs.mkdirSync(folder, { recursive: true });
  const browser = await chromium.launch({
    headless: true,
    args: ['--no-sandbox','--disable-dev-shm-usage','--lang=en-US'],
  });
  const context = await browser.newContext({
    viewport: {width:390,height:844},
    deviceScaleFactor:1,
    reducedMotion:'reduce',
    locale:'en-US',
    timezoneId:'Asia/Kolkata',
  });
  const page = await context.newPage();
  let pageErrors = [];
  page.on('pageerror',e => { pageErrors.push(e.message); console.log('PAGE ERROR',String(e.stack||e.message).slice(0,1200)); });
  page.on('console',m => {if(m.type()==='error')console.log('CONSOLE',m.text().slice(0,600))});
  async function go(route) {
    await page.goto(root+'#'+route,{waitUntil:'load',timeout:60000});
    await page.waitForTimeout(3800);
    const mounted = await page.evaluate(() => !!document.querySelector('flutter-view'));
    if(!mounted)throw new Error('Flutter did not render at '+route);
  }
  async function capture(name) {
    await page.waitForTimeout(1500);
    const file=path.join(folder,name+'.png');
    await page.screenshot({path:file});
    const stat=fs.statSync(file);
    if(stat.size<9000)throw new Error('Likely blank Flutter capture: '+name+' ('+stat.size+' bytes)');
    console.log('CAPTURED',name,stat.size,'bytes');
  }
  // Signed-out data states are real: this review build has no authenticated API.
  await go('/');
  await capture('01_home_dark');
  await go('/market');
  await capture('02_markets_dark');
  await go('/research');
  await capture('03_ai_research_dark');
  await go('/portfolio');
  await capture('04_portfolio_dark');
  await go('/sign-in');
  await capture('05_sign_in_dark');
  await go('/settings');
  await capture('06_settings_dark');

  // Change actual Flutter color mode via Settings. Clicking the segment keeps
  // the widget tree and session state intact, unlike reloading every page.
  await page.mouse.click(110, 220);
  await page.waitForTimeout(800);
  await capture('07_settings_light');
  const nav=async (x,name) => {
    await page.mouse.click(x,809);
    await page.waitForTimeout(1800);
    await capture(name);
  };
  await nav(39,'08_home_light');
  // Home's sole guest sign-in control is in the account card. Tap it to
  // capture the same actual Sign-In widget under the user's light setting.
  await page.mouse.click(192,310);
  await page.waitForTimeout(1500);
  await capture('08a_sign_in_light');
  await page.mouse.click(28,30);
  await page.waitForTimeout(800);
  await nav(117,'09_markets_light');
  await nav(194,'10_ai_research_light');
  await nav(271,'11_portfolio_light');

  const desktop=await browser.newContext({
    viewport:{width:1440,height:900},deviceScaleFactor:1,locale:'en-US',timezoneId:'Asia/Kolkata'
  });
  const desktopPage=await desktop.newPage();
  await desktopPage.goto(root,{waitUntil:'load',timeout:60000});
  await desktopPage.waitForTimeout(6000);
  await desktopPage.screenshot({path:path.join(folder,'12_home_desktop_dark.png')});
  console.log('DESKTOP captured');
  if(pageErrors.length)console.log('PAGE ERRORS TOTAL',pageErrors.length);
  await browser.close();
})().catch(e=>{ console.error(e); process.exit(1); });
