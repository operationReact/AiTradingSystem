import { expect, test } from '@playwright/test';

test('a disappeared selected instrument cannot silently become the first new search result', async ({ page }) => {
  let items = [{id:1,tradingSymbol:'FIRST',exchange:'NSE'},{id:2,tradingSymbol:'SELECTED',exchange:'NSE'}];
  const evaluations=[];
  await page.route('**/api/**', async route => {
    const request=route.request(),path=new URL(request.url()).pathname;
    const reply=body=>route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
    if(path.endsWith('/system/execution-mode'))return reply({activeMode:'PAPER',liveAvailable:false});
    if(path.endsWith('/instruments/search'))return reply({content:items,totalElements:items.length});
    if(path.endsWith('/signals'))return reply([]);
    if(path.includes('/strategies/'))evaluations.push(path);
    return reply({});
  });
  await page.goto('http://127.0.0.1:4173/signals');
  await page.getByLabel('Operator API key',{exact:true}).fill('identity-test-key');
  await page.getByRole('button',{name:'Connect',exact:true}).click();
  await page.getByLabel('NSE equity symbol or name').fill('NSE');
  await page.getByRole('button',{name:'Search',exact:true}).click();
  await page.getByLabel('Matched instrument').selectOption('2');
  await expect(page.getByRole('button',{name:'Evaluate',exact:true})).toBeEnabled();
  items=[items[0]];
  // Simulate a background catalogue refresh while preserving the user's selected identity.
  await page.evaluate(async()=>{const{queryClient}=await import('/src/lib/queryClient.js');await queryClient.invalidateQueries({queryKey:['instruments']});});
  await expect(page.getByLabel('Matched instrument')).toHaveValue('');
  await expect(page.getByRole('button',{name:'Evaluate',exact:true})).toBeDisabled();
  expect(evaluations).toEqual([]);
});
