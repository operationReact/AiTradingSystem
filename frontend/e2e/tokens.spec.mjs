import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

function luminance(rgb) {
  const c=rgb.trim().split(/\s+/).map(Number).map(x=>{const s=x/255;return s<=.04045?s/12.92:((s+.055)/1.055)**2.4;});
  return c[0]*.2126+c[1]*.7152+c[2]*.0722;
}
function contrast(a,b) {const x=luminance(a),y=luminance(b);return(Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
for(const experience of ['pro-terminal','clear-invest']) for(const theme of ['dark','light']) {
  test(`${experience} ${theme}: semantic tokens, readable controls and resolved chart colors`,async({page},info)=>{
    await page.addInitScript(({experience,theme})=>{localStorage.setItem('sf-experience',experience);localStorage.setItem('sf-theme',theme);},{experience,theme});
    await page.route('**/api/**',route=>route.fulfill({contentType:'application/json',body:JSON.stringify(route.request().url().includes('/audit')?{items:[],totalPages:0}:{activeMode:'PAPER',liveAvailable:false})}));
    await page.goto('http://127.0.0.1:4173/audit');
    await page.getByLabel('Operator API key',{exact:true}).fill('token-test-key');
    await page.getByRole('button',{name:'Connect',exact:true}).click();
    await expect(page.getByText('No matching audit events')).toBeVisible();
    const values=await page.evaluate(async()=>{
      const root=document.documentElement,css=getComputedStyle(root);
      const names=['--text','--muted','--surface','--surface-2','--accent','--accent-contrast','--gain','--loss','--warn','--focus-ring','--motion-normal','--radius-panel','--space-4'];
      const tokens=Object.fromEntries(names.map(name=>[name,css.getPropertyValue(name).trim()]));
      const{chartPalette}=await import('/src/lib/chart.js');
      return{tokens,palette:chartPalette(),experience:root.dataset.experience,light:root.classList.contains('light')};
    });
    expect(values.experience).toBe(experience);expect(values.light).toBe(theme==='light');
    for(const value of Object.values(values.tokens))expect(value).not.toBe('');
    for(const fg of ['--text','--muted','--gain','--loss','--warn']) for(const bg of ['--surface','--surface-2']) expect(contrast(values.tokens[fg],values.tokens[bg]),`${fg} on ${bg}`).toBeGreaterThanOrEqual(4.5);
    expect(contrast(values.tokens['--accent'],values.tokens['--accent-contrast'])).toBeGreaterThanOrEqual(4.5);
    expect(values.palette.series).toHaveLength(7);
    expect(values.palette.series.every(color=>!color.includes('var(')&&/^rgb\(/.test(color))).toBe(true);
    const axe=await new AxeBuilder({page}).analyze();
    expect(axe.violations.filter(v=>['serious','critical'].includes(v.impact))).toEqual([]);
    await page.screenshot({path:info.outputPath(`${experience}-${theme}.png`),fullPage:true});
  });
}
test('invalid persisted experience falls back and reduced motion reaches CSS and chart options',async({page})=>{
  await page.addInitScript(()=>localStorage.setItem('sf-experience','untrusted-value'));
  await page.emulateMedia({reducedMotion:'reduce'});
  await page.goto('http://127.0.0.1:4173/audit');
  await expect(page.getByLabel('Appearance — Experience')).toHaveValue('pro-terminal');
  const duration=await page.evaluate(async()=>{const{baseOption,chartPalette}=await import('/src/lib/chart.js');return baseOption(chartPalette()).animationDuration;});
  expect(duration).toBe(0);
  await expect(page.getByRole('button',{name:'Apply filters'})).toHaveCSS('transition-duration','0s');
});
