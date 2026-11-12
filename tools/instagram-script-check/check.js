// Serves a mock Instagram feed at www.instagram.com and runs ScrollStop's script on it.
// Run: node tools/instagram-script-check/check.js app/src/main/res/raw/focus_instagram.js tools/instagram-script-check
const { chromium, devices } = require('playwright');
const fs = require('fs');
(async () => {
  const [scriptPath, dir] = process.argv.slice(2);
  const body = fs.readFileSync(scriptPath, 'utf8');
  const html = fs.readFileSync(dir + '/feed.html', 'utf8');
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ ...devices['Pixel 7'] });
  const page = await ctx.newPage();
  await page.route('https://www.instagram.com/**', r => r.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: html }));
  await page.addInitScript(`window.__hidden = []; window.ScrollStop = { hidden: k => window.__hidden.push(k) };
    var SS_CFG = {"reels":true,"explore":true,"feedReels":true,"ads":true,"suggested":true,"home":false};\n` + body);
  await page.goto('https://www.instagram.com/');
  await page.waitForTimeout(800);
  // infinite scroll: more posts arrive later
  await page.evaluate(() => {
    const f = document.getElementById('feed');
    f.insertAdjacentHTML('beforeend', '<article id="late_ad"><header><div><span>late</span></div></header><div style="height:200px"></div></article>');
    setTimeout(() => { document.querySelector('#late_ad header').insertAdjacentHTML('beforeend', '<span>Sponsored</span>'); }, 100);
  });
  await page.waitForTimeout(1500);
  const state = await page.evaluate(() => {
    const vis = id => { const e = document.getElementById(id); return !!e && getComputedStyle(e).display !== 'none' && !e.closest('[style*="display: none"]'); };
    const ids = ['normal','ad','ad_hi','paid','sugg_label','because','follow_btn','reel','sugg_row','normal2','caught','after1','late_ad'];
    const out = {}; ids.forEach(i => out[i] = vis(i) ? 'shown' : 'HIDDEN');
    out.nav = [...document.querySelectorAll('nav a')].filter(a => a.offsetParent !== null).map(a => a.getAttribute('href'));
    out.counted = window.__hidden.slice().sort();
    return out;
  });
  await page.screenshot({ path: dir + '/feed.png', fullPage: false });
  // A profile page of someone you don't follow must stay visible.
  await page.evaluate(() => {
    history.pushState({}, '', '/bob/');
    document.getElementById('feed').insertAdjacentHTML('beforeend', '<article id="profile_post"><header><div><span>bob</span><div role="button">Follow</div></div></header><div style="height:200px"></div></article>');
  });
  await page.waitForTimeout(800);
  state.profile_post = await page.evaluate(() => getComputedStyle(document.getElementById('profile_post')).display !== 'none' ? 'shown' : 'HIDDEN');
  console.log(JSON.stringify(state, null, 1));
  await browser.close();
})().catch(e => { console.error('ERR', e); process.exit(1); });
