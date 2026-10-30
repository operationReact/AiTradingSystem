import { expect, test } from '@playwright/test';

async function fixture(page) {
  const submissions=[], reads=[];
  await page.route('**/api/**',async route=>{
    const r=route.request(),u=new URL(r.url());
    const reply=(body,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
    if(r.method()==='GET')reads.push(u.pathname);
    if(u.pathname.endsWith('/system/execution-mode'))return reply({activeMode:'PAPER',liveAvailable:false});
    if(u.pathname.endsWith('/paper-trades/simulate')){
      submissions.push({key:r.headers()['idempotency-key'],body:r.postDataJSON()});
      if(submissions.length===1)return reply({detail:'Temporary service failure'},503);
      return reply({order:{id:'order-1',symbol:'TCS',side:'BUY',quantity:1,entryPrice:100,status:'APPROVAL_PENDING'},decision:{id:'decision-1'}});
    }
    if(u.pathname.endsWith('/paper-trades'))return reply({items:[],page:0,size:10,totalItems:0,totalPages:0});
    if(u.pathname.endsWith('/paper-portfolio'))return reply({mode:'PAPER',cashReconciled:true});
    return reply([]);
  });
  await page.goto('http://127.0.0.1:4173/trades?symbol=TCS');
  await page.getByLabel('Operator API key',{exact:true}).fill('order-controller-test-key');
  await page.getByRole('button',{name:'Connect',exact:true}).click();
  await expect(page.getByText('No paper orders',{exact:true})).toBeVisible();
  return{submissions,reads};
}
test('orders controller keeps URL filters and queries across a design switch',async({page})=>{
  const state=await fixture(page);
  await expect(page.getByLabel('Filter by symbol')).toHaveValue('TCS');
  await page.getByLabel('Appearance — Experience').selectOption('clear-invest');
  await expect(page.getByLabel('Filter by symbol')).toHaveValue('TCS');
  await expect(page).toHaveURL(/symbol=TCS/);
  expect(state.reads.filter(path=>path==='/api/paper-trades')).toHaveLength(1);
  await page.getByRole('button',{name:'Clear filters',exact:true}).click();
  await expect(page.getByLabel('Filter by symbol')).toHaveValue('');
  await expect(page).not.toHaveURL(/symbol=/);
  expect(state.submissions).toHaveLength(0);
});
test('open PAPER draft and retry retain one idempotency key through a presentation change',async({page})=>{
  const state=await fixture(page);
  await page.getByRole('button',{name:'New paper order',exact:true}).click();
  const dialog=page.getByRole('dialog');
  await dialog.getByLabel('Symbol',{exact:true}).fill('TCS');
  await dialog.getByLabel('Entry price (₹)',{exact:true}).fill('100');
  await dialog.getByLabel('Stop loss (₹)',{exact:true}).fill('95');
  await dialog.getByLabel('Target (₹)',{exact:true}).fill('110');
  await dialog.getByRole('button',{name:'Submit paper order',exact:true}).click();
  await expect(dialog.getByText('Order not submitted',{exact:true})).toBeVisible();
  await page.getByLabel('Appearance — Experience').selectOption('clear-invest',{force:true});
  await expect(dialog.getByLabel('Symbol',{exact:true})).toHaveValue('TCS');
  await expect(dialog.getByLabel('Entry price (₹)',{exact:true})).toHaveValue('100');
  await dialog.getByRole('button',{name:'Submit paper order',exact:true}).click();
  await expect(dialog.getByText('APPROVAL_PENDING',{exact:true})).toBeVisible();
  expect(state.submissions).toHaveLength(2);
  expect(state.submissions[0].key).toBeTruthy();
  expect(state.submissions[1]).toEqual(state.submissions[0]);
});
