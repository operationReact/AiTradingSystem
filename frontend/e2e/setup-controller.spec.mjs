import { expect, test } from '@playwright/test';

async function fixture(page) {
  const calls=[];let mode='MANUAL';
  await page.route('**/api/**',route=>{
    const r=route.request(),u=new URL(r.url());calls.push({path:u.pathname,url:r.url(),method:r.method(),body:r.postData()});
    const reply=body=>route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
    if(u.pathname.endsWith('/system/execution-mode'))return reply({activeMode:'PAPER',liveAvailable:false});
    if(u.pathname.endsWith('/instruments/status'))return reply({version:'catalogue-1',activeCount:1});
    if(u.pathname.endsWith('/instruments/imports'))return reply([]);
    if(u.pathname.endsWith('/instruments/search'))return reply({content:u.searchParams.get('q')?[{id:1,tradingSymbol:'TCS',exchange:'NSE',symbolToken:'11536',instrumentType:'EQ'}]:[],page:0,totalElements:u.searchParams.get('q')?1:0,totalPages:1});
    if(u.pathname.endsWith('/approval-policies/policy-1')&&r.method()==='PUT'){mode=r.postDataJSON().approvalMode;return reply({});}
    if(u.pathname.endsWith('/approval-policies'))return reply([{id:'policy-1',policyVersion:1,active:true,executionMode:'PAPER',tradingStyle:'SWING',approvalMode:mode}]);
    if(u.pathname.endsWith('/providers/angel-one/status'))return reply({state:'NOT_CONFIGURED'});
    if(u.pathname.endsWith('/market-data/backfills'))return reply([{id:'run-1',instrumentId:3,tradingSymbol:'TCS',exchange:'NSE',symbolToken:'11536',status:'SUCCEEDED',interval:'ONE_DAY',fromDate:'2026-09-01',toDate:'2026-09-30',startedAt:'2026-09-30T05:00:00Z'}]);
    if(u.pathname.endsWith('/market-data/candles'))return reply({items:[],page:0,totalItems:0,totalPages:0,hasNext:false});
    if(u.pathname.endsWith('/market-data/calendar'))return reply({sessions:[]});
    if(u.pathname.endsWith('/market-data/quality/latest'))return reply(null);
    return reply({});
  });
  return calls;
}
async function connect(page,path) {
  await page.goto(`http://127.0.0.1:4173${path}`);
  await page.getByLabel('Operator API key',{exact:true}).fill('setup-controller-test-key');
  await page.getByRole('button',{name:'Connect',exact:true}).click();
}
test('instrument filters survive a design change without starting an import',async({page})=>{
  const calls=await fixture(page);await connect(page,'/instruments');
  await page.getByLabel('Symbol or name',{exact:true}).fill('TCS');
  await page.getByLabel('Exchange',{exact:true}).selectOption('NSE');
  await page.getByLabel('Instrument type',{exact:true}).selectOption('EQ');
  await page.getByLabel('Appearance — Experience').selectOption('clear-invest');
  await expect(page.getByLabel('Symbol or name',{exact:true})).toHaveValue('TCS');
  await expect(page.getByLabel('Exchange',{exact:true})).toHaveValue('NSE');
  await expect(page.getByLabel('Instrument type',{exact:true})).toHaveValue('EQ');
  await page.getByRole('button',{name:'Search',exact:true}).click();
  await expect(page.getByRole('cell',{name:/^TCS/})).toBeVisible();
  expect(calls.filter(c=>c.method==='POST')).toEqual([]);
});
test('market-data forms retain separate historical and operations selections without starting a job',async({page})=>{
  const calls=await fixture(page);await connect(page,'/market-data');
  await page.getByLabel('Instrument from active catalogue',{exact:true}).fill('TCS');
  await page.locator('form').filter({has:page.getByLabel('Instrument from active catalogue',{exact:true})}).getByRole('button',{name:/TCS/}).click();
  await page.getByLabel('Candle interval',{exact:true}).selectOption('ONE_HOUR');
  await page.getByLabel('From date',{exact:true}).fill('2026-10-01');
  await page.getByLabel('To date',{exact:true}).fill('2026-10-20');
  await page.getByLabel('Instrument ID',{exact:true}).fill('2');
  await page.getByLabel('Interval',{exact:true}).selectOption('ONE_DAY');
  await page.getByLabel('Appearance — Experience').selectOption('clear-invest');
  await expect(page.getByText('TCS',{exact:true}).first()).toBeVisible();
  await expect(page.getByLabel('Candle interval',{exact:true})).toHaveValue('ONE_HOUR');
  await expect(page.getByLabel('From date',{exact:true})).toHaveValue('2026-10-01');
  await expect(page.getByLabel('To date',{exact:true})).toHaveValue('2026-10-20');
  await expect(page.getByLabel('Instrument ID',{exact:true})).toHaveValue('2');
  await expect(page.getByLabel('Interval',{exact:true})).toHaveValue('ONE_DAY');
  await page.getByRole('button',{name:'View',exact:true}).click();
  await expect(page.getByLabel('From date',{exact:true})).toHaveValue('2026-09-01');
  await expect(page.getByLabel('To date',{exact:true})).toHaveValue('2026-09-30');
  await expect(page.getByLabel('Candle interval',{exact:true})).toHaveValue('ONE_DAY');
  await page.getByLabel('Instrument ID',{exact:true}).fill('2.5');
  await expect(page.getByRole('button',{name:'Evaluate quality',exact:true})).toBeDisabled();
  expect(calls.filter(c=>c.method==='POST')).toEqual([]);
  await page.getByRole('button',{name:'Clear operator API key',exact:true}).click();
  await expect(page.getByRole('button',{name:'Evaluate quality',exact:true})).toBeDisabled();
  await expect(page.getByRole('button',{name:'Reconcile gaps',exact:true})).toBeDisabled();
  await expect(page.getByRole('button',{name:'Fetch & store candles',exact:true})).toBeDisabled();
});
test('settings change approval mode only through an explicit authenticated callback',async({page})=>{
  const calls=await fixture(page);await connect(page,'/settings');
  await expect(page.getByRole('button',{name:'MANUAL',exact:true})).toBeDisabled();
  await page.getByLabel('Appearance — Experience').selectOption('clear-invest');
  expect(calls.filter(c=>c.method==='PUT')).toEqual([]);
  await page.getByRole('button',{name:'AUTO',exact:true}).click();
  await expect.poll(()=>calls.filter(c=>c.method==='PUT').length).toBe(1);
  await expect(page.getByRole('button',{name:'AUTO',exact:true})).toBeDisabled();
  expect(JSON.parse(calls.find(c=>c.method==='PUT').body)).toEqual({approvalMode:'AUTO'});
});
