const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const base = 'http://127.0.0.1:8765/';
  const folder = path.resolve(__dirname, '../../apps/trading_client/captured-screens');
  fs.mkdirSync(folder, { recursive: true });
  const browser = await chromium.launch({headless: true, args:['--no-sandbox','--disable-dev-shm-usage']});
  const context = await browser.newContext({viewport: {width:390,height:844}, deviceScaleFactor:1, reducedMotion:'reduce'});
  const page = await context.newPage();
  page.on('pageerror',error => console.log('PAGE ERROR',error.message.slice(0,300)));
  const pause = (ms) => page.waitForTimeout(ms);
  const save = async (name) => {
    await pause(1400);
    await page.screenshot({path:path.join(folder,name+'.png')});
    console.log('Captured',name,page.url());
  };
  await page.goto(base, {waitUntil:'load',timeout:60000});
  await pause(7000);
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
  // Desktop view from same compiled frontend
  const desktop = await browser.newPage({viewport:{width:1440,height:900}, deviceScaleFactor:1});
  await desktop.goto(base,{waitUntil:'load',timeout:60000});
  await desktop.waitForTimeout(5000);
  await desktop.screenshot({path:path.join(folder,'08_home_desktop_dark.png')});
  console.log('Captured desktop');
  await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
