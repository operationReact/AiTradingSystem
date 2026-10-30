import { expect, test } from '@playwright/test';

const hidden=['kpis','equity','allocation','pnl','drawdown','signals','risk','positions','trades','agents'];
async function fixture(page) {
  const calls=[];
  await page.route('**/api/**',route=>{
    const r=route.request(),u=new URL(r.url());calls.push({path:u.pathname,url:r.url(),method:r.method(),bearer:r.headers().authorization});
    const reply=body=>route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
    if(u.pathname.endsWith('/system/execution-mode'))return reply({activeMode:'PAPER',liveAvailable:false});
    if(u.pathname.endsWith('/dashboard/summary'))return reply({providers:[],agents:[],mode:'PAPER',asOf:new Date().toISOString()});
    if(u.pathname.endsWith('/dashboard/equity-curve')||u.pathname.endsWith('/signals'))return reply([]);
    if(u.pathname.endsWith('/paper-trades/portfolio'))return reply({positions:[]});
    if(u.pathname.endsWith('/paper-trades'))return reply({items:[]});
    if(u.pathname.endsWith('/news/status'))return reply({configured:false,provider:'NONE'});
    if(u.pathname.endsWith('/news/articles'))return reply({items:[]});
    if(u.pathname.endsWith('/news'))return reply({items:[{id:'rss-1',title:'Source-linked market report',sourceUrl:'https://example.com/report',publisher:'Fixture publisher',ingestionStatus:'INGESTED',linkedSymbols:['TCS']}]});
    if(u.pathname.includes('/providers/angel-one/'))return reply({state:'NOT_CONFIGURED',sessionActive:false});
    return reply({});
  });
  return calls;
}
test('overview keeps widget queries, RSS memory-only credential and explicit actions across a stale presentation switch',async({page})=>{
  await page.clock.install();
  await page.addInitScript(hidden=>localStorage.setItem('sf-dashboard-hidden',JSON.stringify(hidden)),hidden);
  const calls=await fixture(page);
  await page.goto('http://127.0.0.1:4173/');
  await page.getByLabel('News feed bearer token').fill('rss-memory-test-token');
  await page.getByLabel('Operator API key',{exact:true}).fill('dashboard-test-key');
  await page.getByRole('button',{name:'Connect',exact:true}).click();
  await expect(page.getByText('Not configured',{exact:true})).toBeVisible();
  await expect(page.getByText('No news articles ingested',{exact:true})).toBeVisible();
  expect(calls.filter(c=>c.path.endsWith('/news/status'))).toHaveLength(1);
  expect(calls.filter(c=>c.path.endsWith('/providers/angel-one/status'))).toHaveLength(1);
  await page.clock.fastForward(16000); // Cached widget data is stale; a remount would refetch it.
  await page.getByLabel('Appearance — Experience').selectOption('clear-invest');
  await expect(page.getByRole('heading',{name:'Your paper portfolio'})).toBeVisible();
  await expect(page.getByLabel('News feed bearer token')).toHaveValue('rss-memory-test-token');
  expect(calls.filter(c=>c.path.endsWith('/news/status'))).toHaveLength(1);
  expect(calls.filter(c=>c.path.endsWith('/providers/angel-one/status'))).toHaveLength(1);
  expect(calls.filter(c=>c.method==='POST')).toHaveLength(0);
  expect(calls.filter(c=>c.path==='/api/news')).toHaveLength(0);
  await page.getByRole('button',{name:'Load feed',exact:true}).click();
  await expect(page.getByRole('link',{name:'Source-linked market report'})).toBeVisible();
  expect(calls.find(c=>c.path==='/api/news').bearer).toBe('Bearer rss-memory-test-token');
  await page.getByRole('button',{name:'Test connection',exact:true}).click();
  await expect.poll(()=>calls.filter(c=>c.method==='POST').length).toBe(1);
  await page.getByRole('button',{name:'Widgets',exact:true}).click();
  await page.getByLabel('News intelligence',{exact:true}).uncheck();
  await page.getByRole('radio',{name:'90D',exact:true}).click();
  await page.getByLabel('Appearance — Experience').selectOption('pro-terminal');
  await expect(page.getByLabel('News intelligence',{exact:true})).not.toBeChecked();
  await expect(page.getByRole('radio',{name:'90D',exact:true})).toHaveAttribute('aria-checked','true');
  expect(await page.evaluate(()=>JSON.stringify(Object.values(localStorage)))).not.toContain('rss-memory-test-token');
});
test('malformed stored widget/range preferences fall back to a usable overview',async({page})=>{
  await page.addInitScript(()=>{localStorage.setItem('sf-dashboard-hidden','{"unexpected":true}');localStorage.setItem('sf-dashboard-range','bad-range');});
  await fixture(page);
  await page.goto('http://127.0.0.1:4173/');
  await expect(page.getByRole('heading',{name:'Overview',exact:true})).toBeVisible();
  await expect(page.getByRole('radio',{name:'30D',exact:true})).toHaveAttribute('aria-checked','true');
  await page.getByRole('button',{name:'Widgets',exact:true}).click();
  await expect(page.getByLabel('RSS news feed',{exact:true})).toBeChecked();
});
