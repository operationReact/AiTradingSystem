const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const base = 'http://127.0.0.1:8765/';
  const folder = path.resolve(__dirname, '../../apps/trading_client/captured-screens');
  fs.mkdirSync(folder, { recursive: true });
  const browser = await chromium.launch({headless: true, args:['--no-sandbox','--disable-dev-shm-usage','--lang=en-US']});
  const context = await browser.newContext({viewport: {width:390,height:844}, deviceScaleFactor:1, reducedMotion:'reduce', locale:'en-US', timezoneId:'Asia/Kolkata'});
  const page = await context.newPage();
  page.on('pageerror',error => console.log('PAGE ERROR',String(error.stack || error.message).slice(0,1800)));
  page.on('console',message => { if (message.type()==='error') console.log('CONSOLE ERROR',message.text().slice(0,900)); });
  const pause = (ms) => page.waitForTimeout(ms);
  const save = async (name) => {
    await pause(1400);
    await page.screenshot({path:path.join(folder,name+'.png')});
    console.log('Captured',name,page.url());
  };
  await page.goto(base, {waitUntil:'load',timeout:60000});
  await pause(7000);
  console.log('BROWSER LOCALE', await page.evaluate(() => ({language:navigator.language, languages:navigator.languages, tz:Intl.DateTimeFormat().resolvedOptions().timeZone, root:!!document.querySelector('flutter-view')})));
  if (!(await page.evaluate(() => !!document.querySelector('flutter-view')))) throw new Error('Flutter view was not created; refusing blank screenshots');
  await save('01_home_dark');
  // Bottom nav is fixed at the bottom of the actual 390x844 Flutter shell.
  const nav = async (x,name) => {
    await page.mouse.click(x,809);
    await pause(1900);
    await save(name);
  };
  await nav(118,'02_markets_dark');
  await nav(194,'03_ai_research_dark');
  await nav(271,'04_portfolio_dark');
  await nav(348,'05_more_sheet_dark');
  await page.goto(base+'#/sign-in', {waitUntil:'load',timeout:60000});
  await pause(4000);
  await save('06_sign_in_deeplink');
  await page.goto(base+'#/settings', {waitUntil:'load',timeout:60000});
  await pause(4000);
  await save('07_settings_deeplink');
  // Switch via MintTick's implemented Settings control, then capture the
  // very same routes under the actual light-theme state. No theme injection.
  await page.mouse.click(190, 666);
  await save('09_settings_light');
  await nav(39,'10_home_light');
  await page.mouse.click(192, 502);
  await save('11_sign_in_light');
  await nav(117,'12_markets_light');
  await nav(194,'13_ai_research_light');
  await nav(271,'14_portfolio_light');
  // Desktop view from same compiled frontend
  const desktopContext = await browser.newContext({viewport:{width:1440,height:900},deviceScaleFactor:1,locale:'en-US',timezoneId:'Asia/Kolkata'});
  const desktop = await desktopContext.newPage();
  desktop.on('pageerror',error => console.log('DESKTOP ERROR', String(error.stack || error.message).slice(0,1500)));
  await desktop.goto(base,{waitUntil:'load',timeout:60000});
  await desktop.waitForTimeout(5000);
  await desktop.screenshot({path:path.join(folder,'08_home_desktop_dark.png')});
  console.log('Captured desktop');
  await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
