const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require('playwright');
const runtime = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/motion-review.js'), 'utf8');

async function browserPage(html, fn) {
  const browser = await chromium.launch({executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox']});
  try {
    const page = await browser.newPage({viewport: {width: 393, height: 852}});
    await page.setContent(html);
    await page.addScriptTag({content: runtime});
    await fn(page);
  } finally { await browser.close(); }
}

test('controlled timeline seeks backwards and gives repeatable actual screenshots', async () => {
  await browserPage(`<style>body{margin:0}#b{width:40px;height:40px;background:red}</style><div id=b></div>
    <script>window.__motion={durationMs:1000,ready:Promise.resolve(),seek(ms){b.style.transform='translateX('+ms/10+'px)'}};</script>`, async page => {
    await page.evaluate(() => __fgMotionReview.seek(250));
    const first = await page.screenshot();
    assert.equal(await page.locator('#b').evaluate(el => el.getBoundingClientRect().x), 25);
    await page.evaluate(() => __fgMotionReview.seek(900));
    assert.notDeepEqual(first, await page.screenshot());
    await page.evaluate(() => __fgMotionReview.seek(250));
    assert.deepEqual(first, await page.screenshot());
    await assert.rejects(page.evaluate(() => __fgMotionReview.seek(1001)), /超过作品时长/);
  });
});

test('CSS animations use their animation clock rather than real waiting time', async () => {
  await browserPage(`<style>body{margin:0}#b{width:40px;height:40px;background:red;animation:move 1s linear both}@keyframes move{to{transform:translateX(100px)}}</style><div id=b></div>`, async page => {
    await page.evaluate(() => __fgMotionReview.seek(500));
    assert.equal(await page.locator('#b').evaluate(el => el.getBoundingClientRect().x), 50);
    await page.waitForTimeout(150);
    assert.equal(await page.locator('#b').evaluate(el => el.getBoundingClientRect().x), 50);
    await page.evaluate(() => __fgMotionReview.seek(0));
    assert.equal(await page.locator('#b').evaluate(el => el.getBoundingClientRect().x), 0);
  });
});

test('uncontrolled canvas and missing assets fail explicitly', async () => {
  await browserPage('<canvas></canvas>', async page => {
    await assert.rejects(page.evaluate(() => __fgMotionReview.seek(0)), /需要实现/);
  });
  await browserPage('<img src="data:image/png;base64,invalid"><script>window.__motion={durationMs:1000,seek(){}}</script>', async page => {
    await assert.rejects(page.evaluate(() => __fgMotionReview.seek(0)), /图片未加载/);
  });
});

test('async initialization finishes before an animation is inspected', async () => {
  await browserPage(`<div id=b></div><script>let initialized=false;window.__motion={durationMs:1000,
    ready:new Promise(r=>setTimeout(()=>{initialized=true;r()},100)),seek(ms){if(!initialized)throw Error('early');b.textContent=ms}};</script>`, async page => {
    await page.evaluate(() => __fgMotionReview.seek(700));
    assert.equal(await page.locator('#b').textContent(), '700');
  });
});
