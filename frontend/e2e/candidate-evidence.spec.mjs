import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { candidate, evaluation, scenario } from './fixtures/candidate.mjs';
async function fixture(page, override={}, empty=false) {
  const calls=[];
  await page.route('**/api/**',async route=>{
    const r=route.request(),path=new URL(r.url()).pathname;
    calls.push({path,method:r.method(),body:r.postDataJSON(),key:r.headers()['x-api-key']});
    const reply=body=>route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
    if(path.endsWith('/system/execution-mode'))return reply({activeMode:'PAPER',liveAvailable:false});
    if(path.endsWith('/candidates/42'))return reply(candidate);
    if(path.endsWith('/candidates/42/evaluate'))return reply({...evaluation,scenarioReused:true});
    if(path.endsWith('/candidates/42/evaluations'))return reply(empty?[]:[evaluation]);
    if(path.endsWith('/simulations/scenario-1'))return reply({...scenario,...override});
    return reply({});
  });return calls;
}
async function connect(page, experience='pro-terminal',width=1440) {
  await page.setViewportSize({width,height:1000});await page.goto('http://127.0.0.1:4173/research/candidates/42');
  await page.getByLabel('Appearance — Experience').selectOption(experience);
  await page.getByLabel('Operator API key',{exact:true}).fill('candidate-test-key');
  await page.getByRole('button',{name:'Connect',exact:true}).click();
}
for(const [experience,width] of [['pro-terminal',1440],['clear-invest',375]])test(`${experience}: explicit research and provenance survive design switching`,async({page})=>{
  const calls=await fixture(page);await connect(page,experience,width);
  await expect(page.getByText('55.0%',{exact:true})).toBeVisible();await expect(page.getByText('-1.02 R',{exact:true})).toBeVisible();
  await page.getByLabel('Reproducibility seed',{exact:true}).fill('424242');
  await page.getByLabel('Appearance — Experience').selectOption(experience==='pro-terminal'?'clear-invest':'pro-terminal');
  await expect(page.getByLabel('Reproducibility seed',{exact:true})).toHaveValue('424242');
  expect(calls.filter(c=>c.method==='POST')).toHaveLength(0);
  await page.getByRole('button',{name:'Evaluate research',exact:true}).click();
  await expect(page.getByText('Evaluation recorded using the existing reproducible scenario.',{exact:true})).toBeVisible();
  const posts=calls.filter(c=>c.method==='POST');expect(posts).toHaveLength(1);
  expect(posts[0].body).toEqual({historyFrom:'2026-01-01T09:55:00.000Z',historyTo:'2026-06-30T09:55:00.000Z',simulations:1000,horizonBars:20,blockSize:5,seed:424242,slippageBps:5,roundTripCostBps:10});expect(posts[0].key).toBe('candidate-test-key');
  await page.getByRole('button',{name:'View methodology and provenance'}).click();
  await expect(page.getByRole('dialog')).toContainText('SESSION_REGIME_BLOCK_BOOTSTRAP_V2');await expect(page.getByRole('dialog')).toContainText(candidate.candidateFingerprint);
  await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth-document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
  const axe=await new AxeBuilder({page}).analyze();expect(axe.violations.filter(v=>['serious','critical'].includes(v.impact))).toEqual([]);
  await page.getByRole('button',{name:'Clear operator API key',exact:true}).click();
  await expect(page.getByRole('button',{name:'Evaluate research',exact:true})).toBeDisabled();await expect(page.getByText('55.0%',{exact:true})).toHaveCount(0);
  expect(calls.filter(c=>/orders|approvals/.test(c.path))).toEqual([]);
});
for(const [name,override,message] of [
 ['wrong candidate',{candidateId:99},'Persisted candidate and scenario provenance do not match.'],
 ['invalid probabilities',{targetFirstProbability:1.5},'Stored scenario probabilities are invalid.'],
 ['failed run',{status:'FAILED',errorCode:'INSUFFICIENT_HISTORY'},'Scenario results unavailable: FAILED. INSUFFICIENT_HISTORY'],
])test(`reject ${name}`,async({page})=>{
 await fixture(page,override);await connect(page);await expect(page.getByText(message,{exact:true})).toBeVisible();await expect(page.getByRole('region',{name:'Scenario outcome frequencies'})).toHaveCount(0);await expect(page.getByRole('button',{name:'View methodology and provenance'})).toHaveCount(0);
});
test('empty history and invalid request never create fabricated evidence',async({page})=>{
 const calls=await fixture(page,{},true);await connect(page);await expect(page.getByText('No recorded evaluations',{exact:true})).toBeVisible();
 await page.getByLabel('Sampling block (bars)',{exact:true}).fill('21');await expect(page.getByRole('button',{name:'Evaluate research',exact:true})).toBeDisabled();
 await page.getByLabel('Sampling block (bars)',{exact:true}).fill('5');await page.getByLabel('History end (IST)',{exact:true}).fill('2026-07-01T15:25');await expect(page.getByText('History must end at or before the candidate data cutoff.',{exact:true})).toBeVisible();expect(calls.filter(c=>c.method==='POST')).toEqual([]);
});
