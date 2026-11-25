import { expect, test } from '@playwright/test';

for (const experience of ['pro-terminal','clear-invest']) {
  test(`${experience}: research form survives variant remount, navigates persisted identity and stops requests after key clear`,async({page})=>{
    const posts=[], requests=[];
    await page.route('**/api/**',async route=>{
      const request=route.request(), url=new URL(request.url());
      requests.push({path:url.pathname,key:request.headers()['x-api-key']});
      const reply=body=>route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
      if(url.pathname.endsWith('/system/execution-mode'))return reply({activeMode:'PAPER',liveAvailable:false});
      if(url.pathname.endsWith('/research/strategies'))return reply([]);
      if(url.pathname.endsWith('/research/scans')&&request.method()==='POST'){
        posts.push({url:request.url(),key:request.headers()['x-api-key']});
        return reply({id:'scan-1',status:'SUCCEEDED',candidateCount:1,scannedCount:17,requestedLimit:17});
      }
      if(url.pathname.endsWith('/research/scans/scan-1'))return reply({id:'scan-1',status:'SUCCEEDED',candidateCount:1});
      if(url.pathname.endsWith('/candidates'))return reply([{candidateId:42,tradingSymbol:'TCS',exchange:'NSE',interval:'THREE_MINUTE',strategy:'SMA',score:.8,referencePrice:100,suggestedStop:95,suggestedTarget:110,reasons:['STORED_EVIDENCE']}]);
      return reply([]);
    });
    await page.goto('http://127.0.0.1:4173/research');
    await page.getByLabel('Appearance — Experience').selectOption(experience);
    await expect(page.getByRole('button',{name:'Start scan',exact:true})).toBeDisabled();
    await page.getByLabel('Operator API key',{exact:true}).fill('controller-test-key');
    await page.getByRole('button',{name:'Connect',exact:true}).click();
    await page.getByLabel('Universe limit').fill('17');
    await page.getByLabel('Interval',{exact:true}).selectOption('THREE_MINUTE');
    await page.getByLabel('Appearance — Experience').selectOption(experience==='pro-terminal'?'clear-invest':'pro-terminal');
    await expect(page.getByLabel('Universe limit')).toHaveValue('17');
    await expect(page.getByLabel('Interval',{exact:true})).toHaveValue('THREE_MINUTE');
    await page.getByRole('button',{name:'Start scan',exact:true}).click();
    const candidate=page.getByRole('link',{name:'TCS',exact:true});
    await expect(candidate).toHaveAttribute('href','/research/candidates/42');
    expect(posts).toHaveLength(1);
    expect(posts[0].url).toContain('interval=THREE_MINUTE&limit=17');
    expect(posts[0].key).toBe('controller-test-key');
    const before=requests.length;
    await page.getByRole('button',{name:'Clear operator API key',exact:true}).click();
    await expect(page.getByRole('button',{name:'Start scan',exact:true})).toBeDisabled();
    await expect(page.getByRole('heading',{name:'Strategy scanner'})).toBeVisible();
    // Disabled anonymous queries must not be accidentally re-enabled by per-scan options.
    await expect.poll(()=>requests.slice(before).filter(r=>!r.key).length).toBe(0);
    expect(requests.filter(r=>r.path.includes('/research/')&&!r.key)).toEqual([]);
  });
}
