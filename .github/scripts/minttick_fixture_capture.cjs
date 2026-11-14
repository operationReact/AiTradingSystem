const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const folder = path.resolve(__dirname, '../../apps/trading_client/fixture-captures');
  fs.mkdirSync(folder,{recursive:true});
  const browser = await chromium.launch({
    headless:true,
    args:['--no-sandbox','--disable-dev-shm-usage','--lang=en-US'],
  });
  const context = await browser.newContext({
    viewport:{width:390,height:844},deviceScaleFactor:1,
    locale:'en-US',timezoneId:'Asia/Kolkata',reducedMotion:'reduce',
  });
  const page=await context.newPage();
  page.on('pageerror',e=>console.log('PAGE ERROR',String(e.stack||e.message).slice(0,1000)));
  const root='http://127.0.0.1:8765/';
  async function open(theme) {
    await page.goto(root+'?theme='+theme,{waitUntil:'load',timeout:60000});
    await page.waitForTimeout(6500);
    const view=await page.evaluate(()=>!!document.querySelector('flutter-view'));
    if(!view)throw Error('Flutter did not mount');
  }
  async function capture(name) {
    await page.waitForTimeout(1100);
    const output=path.join(folder,name+'.png');
    await page.screenshot({path:output});
    const bytes=fs.statSync(output).size;
    if(bytes<9000)throw Error('Rejecting blank capture '+name);
    console.log('CAPTURED',name,bytes);
  }
  for(const theme of ['dark','light']){
    await open(theme);
    await capture('markets_01_catalogue_'+theme);
    await page.mouse.move(190,600);
    await page.mouse.wheel(0,2500);
    await page.waitForTimeout(1750);
    await capture('markets_02_scroll_'+theme);
    await open(theme);
    await page.mouse.click(90,330);
    await page.waitForTimeout(1700);
    await capture('markets_03_detail_'+theme);
  }
  await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
