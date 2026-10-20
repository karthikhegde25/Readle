// Drives the exact HTML the Kotlin HtmlInjector generates inside real Chromium, with a stub native bridge.
const { chromium } = require('/opt/node-tools/node_modules/playwright-core');
const fs = require('fs');
const path = require('path');

const PAGES = process.argv[2];
const csp = fs.readFileSync(path.join(PAGES, 'csp.txt'), 'utf8');
let failures = 0, passes = 0;
function check(name, cond, extra) {
  if (cond) { passes++; console.log('  ok   - ' + name); }
  else { failures++; console.log('  FAIL - ' + name + (extra !== undefined ? '  => ' + JSON.stringify(extra) : '')); }
}
const near = (a, b, tol) => Math.abs(a - b) <= tol;

async function openPage(browser, file, { waitMs = 600, touch = false } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 800 }, hasTouch: touch });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    window.__calls = [];
    const rec = (n) => (...a) => { window.__calls.push([n, ...a]); };
    window.ReadleBridge = {
      onScroll: rec('onScroll'), onPages: rec('onPages'), onTap: rec('onTap'), onSelection: rec('onSelection'),
      onSelectionCleared: rec('onSelectionCleared'), onNavigate: rec('onNavigate'),
      onInternalJump: rec('onInternalJump'), onHighlightTap: rec('onHighlightTap'), onReady: rec('onReady')
    };
  });
  const consoleErrors = [];
  page.on('pageerror', e => consoleErrors.push(String(e)));
  await page.route('https://readle.local/**', route => {
    const url = new URL(route.request().url());
    const name = path.basename(url.pathname);
    const file = path.join(PAGES, name);
    if (fs.existsSync(file)) {
      route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: fs.readFileSync(file), headers: { 'Content-Security-Policy': csp } });
    } else {
      route.fulfill({ status: 404, body: 'nf' });
    }
  });
  await page.goto('https://readle.local/book/' + file);
  await page.waitForTimeout(waitMs);
  return { ctx, page, consoleErrors };
}

const calls = (page, name) => page.evaluate(n => window.__calls.filter(c => c[0] === n), name);
const scrollFrac = page => page.evaluate(() => window.readle.fraction());

(async () => {
  const browser = await chromium.launch();

  console.log('# restore + CSP + stylesheet precedence (frac.html)');
  {
    const { ctx, page, consoleErrors } = await openPage(browser, 'frac.html', { waitMs: 1500 });
    check('restores 50% position', near(await scrollFrac(page), 0.5, 0.02), await scrollFrac(page));
    check('book script blocked by CSP', await page.evaluate(() => window.BOOK_SCRIPT_RAN === undefined));
    check('reader script ran (window.readle present)', await page.evaluate(() => typeof window.readle === 'object'));
    check('no uncaught page errors', consoleErrors.length === 0, consoleErrors);
    const style = await page.evaluate(() => {
      const p = document.querySelector('#p5'); const cs = getComputedStyle(p);
      return { color: cs.color, size: cs.fontSize, bg: getComputedStyle(document.body).backgroundColor };
    });
    check('reader colour overrides book !important colour', style.color === 'rgb(27, 27, 27)', style);
    check('user font size beats absolute px in book css (16px at 100%)', style.size === '16px', style);
    check('no scroll reports before the page is armed', (await calls(page, 'onScroll')).length === 0, await calls(page, 'onScroll'));
    await page.waitForTimeout(1600);
    const reps = await calls(page, 'onScroll');
    check('armed report arrives with restored fraction', reps.length >= 1 && near(reps[reps.length - 1][1], 0.5, 0.02), reps.slice(-1));
    await ctx.close();
  }

  console.log('# selection offsets, highlights and unwrap');
  {
    const { ctx, page } = await openPage(browser, 'frac.html', { waitMs: 400 });
    const expected = await page.evaluate(() => {
      const hd = document.getElementById('rl-header'); const t = document.body.textContent.slice(hd ? hd.textContent.length : 0); const s = t.indexOf('beta gamma'); return { s, e: s + 'beta gamma'.length };
    });
    await page.evaluate(() => {
      const em = document.querySelector('#multi em');
      const r = document.createRange();
      r.setStart(em.firstChild, 0);                 // "beta " text node
      const b = em.querySelector('b');
      r.setEnd(b.firstChild, b.firstChild.length);  // end of "gamma"
      const sel = getSelection(); sel.removeAllRanges(); sel.addRange(r);
    });
    await page.waitForTimeout(500);
    const sels = await calls(page, 'onSelection');
    check('selection reported once', sels.length >= 1, sels);
    const [, text, s, e] = sels[sels.length - 1] || [];
    check('selected text', text === 'beta gamma', text);
    check('start offset equals independent textContent offset', s === expected.s, { s, expected });
    check('end offset equals independent textContent offset', e === expected.e, { e, expected });

    const before = await page.evaluate(() => document.body.textContent.replace(/\s+/g, ' '));
    await page.evaluate(([s, e]) => { window.readle.clearSelection(); window.readle.applyHighlights([{ id: 1, start: s, end: e, color: '#FFE066' }]); }, [s, e]);
    const marks = await page.evaluate(() => Array.from(document.querySelectorAll('mark.rl-hl')).map(m => m.textContent));
    check('highlight wraps exactly the selected text across nested elements', marks.join('') === 'beta gamma' && marks.length >= 2, marks);
    const afterWrap = await page.evaluate(() => document.body.textContent.replace(/\s+/g, ' '));
    check('wrapping never changes the text content', before === afterWrap);
    // Highlights are re-applied from scratch each time, including right on top of existing ones.
    await page.evaluate(([s, e]) => window.readle.applyHighlights([{ id: 1, start: s, end: e, color: '#FFE066' }, { id: 2, start: s + 2, end: s + 6, color: '#A8E6A1' }]), [s, e]);
    const ids = await page.evaluate(() => Array.from(new Set(Array.from(document.querySelectorAll('mark.rl-hl')).map(m => m.dataset.id))).sort());
    check('overlapping highlights both rendered', ids.join(',') === '1,2', ids);
    await page.evaluate(() => window.readle.applyHighlights([]));
    const left = await page.evaluate(() => document.querySelectorAll('mark.rl-hl').length);
    const afterClear = await page.evaluate(() => document.body.textContent.replace(/\s+/g, ' '));
    check('clearing highlights removes every mark and restores text', left === 0 && afterClear === before);
    await ctx.close();
  }

  console.log('# initial highlight from page init + tap on highlight (hl.html)');
  {
    const { ctx, page } = await openPage(browser, 'hl.html', { waitMs: 500 });
    const info = await page.evaluate(() => {
      const ms = Array.from(document.querySelectorAll('mark.rl-hl'));
      return { text: ms.map(m => m.textContent).join(''), expect: document.body.textContent.slice(14, 38), n: ms.length, border: ms[0] && getComputedStyle(ms[0]).borderBottomStyle, id: ms[0] && ms[0].dataset.id };
    });
    check('highlight from init json rendered over the right characters', info.n > 0 && info.text === info.expect, info);
    check('note highlight gets dotted underline', info.border === 'dotted', info);
    await page.click('mark.rl-hl');
    const taps = await calls(page, 'onHighlightTap');
    check('tapping a highlight reports its id', taps.length === 1 && taps[0][1] === 7, taps);
    check('tapping a highlight does not toggle the toolbar', (await calls(page, 'onTap')).length === 0);
    await ctx.close();
  }

  console.log('# find in chapter');
  {
    const { ctx, page } = await openPage(browser, 'frac.html', { waitMs: 400 });
    const res = [];
    for (let i = 0; i < 4; i++) {
      res.push(await page.evaluate(n => { const ok = window.readle.findText('needle', n); const m = document.querySelector('mark.rl-find'); return [ok, m && m.textContent]; }, i));
    }
    check('1st occurrence', res[0][0] === true && res[0][1] === 'needle', res[0]);
    check('2nd occurrence is case-insensitive', res[1][0] === true && res[1][1] === 'Needle', res[1]);
    check('3rd occurrence', res[2][0] === true && res[2][1] === 'NEEDLE', res[2]);
    check('4th occurrence does not exist', res[3][0] === false, res[3]);
    const multi = await page.evaluate(() => { const ok = window.readle.findText('  Alpha   beta  gamma ', 0); return [ok, Array.from(document.querySelectorAll('mark.rl-find')).map(m => m.textContent).join('')]; });
    check('multi-word query ignores whitespace differences and crosses inline tags', multi[0] === true && multi[1].replace(/\s+/g, ' ') === 'Alpha beta gamma', multi);
    await page.evaluate(() => window.readle.findText('needle', 2));
    const visible = await page.evaluate(() => { const r = document.querySelector('mark.rl-find').getBoundingClientRect(); return r.top >= 0 && r.bottom <= innerHeight; });
    check('found text is scrolled into view', visible);
    await page.evaluate(() => window.readle.clearFind());
    check('clearFind removes marks', await page.evaluate(() => document.querySelectorAll('mark.rl-find').length === 0));
    await ctx.close();
  }

  console.log('# taps, links, footer and paging');
  {
    const { ctx, page } = await openPage(browser, 'anchor.html', { waitMs: 500 });
    check('anchor target is at the top of the screen', await page.evaluate(() => { const r = document.getElementById('p40').getBoundingClientRect(); return Math.abs(r.top) < 4; }));
    await page.evaluate(() => document.getElementById('p10').scrollIntoView({ block: 'center' }));
    const box = await page.evaluate(() => { const r = document.getElementById('p10').getBoundingClientRect(); return { y: r.top + r.height / 2 }; });
    await page.mouse.click(195, box.y);
    const taps = await calls(page, 'onTap');
    check('tap on text reports normalised x', taps.length === 1 && near(taps[0][1], 0.5, 0.01), taps);
    await page.mouse.click(40, box.y);
    const taps2 = await calls(page, 'onTap');
    check('tap near the left edge reports a small x (page-back zone)', taps2.length === 2 && taps2[1][1] < 0.2, taps2);
    // The native WebViewClient intercepts link navigation; here we just stop Chromium navigating away.
    await page.evaluate(() => document.addEventListener('click', e => { if (e.target.closest && e.target.closest('a')) e.preventDefault(); }, true));
    await page.evaluate(() => document.getElementById('ext').scrollIntoView({ block: 'center' }));
    const ly = await page.evaluate(() => { const l = document.getElementById('ext').getBoundingClientRect(); return [l.left + 5, l.top + l.height / 2]; });
    await page.mouse.click(ly[0], ly[1]);
    check('tapping a link is not reported as a page tap', (await calls(page, 'onTap')).length === 2);
    await page.evaluate(() => window.__calls.length = 0);
    await page.evaluate(() => { window.__calls.length = 0; });
    await page.evaluate(() => document.querySelector('#jump').scrollIntoView());
    await page.click('#jump');
    const jumps = await calls(page, 'onInternalJump');
    check('in-page link reports the position it left', jumps.length === 1, jumps);
    check('in-page link is not treated as a tap', (await calls(page, 'onTap')).length === 0);
    check('footer shows no "previous" on the first chapter', await page.evaluate(() => !Array.from(document.querySelectorAll('#rl-footer button')).some(b => /Previous/.test(b.textContent))));
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await page.click('#rl-footer button');
    const nav = await calls(page, 'onNavigate');
    check('footer button asks native to go to next chapter', nav.length === 1 && nav[0][1] === 'next', nav);
    check('footer is not counted in text offsets', await page.evaluate(() => { window.readle.applyHighlights([{ id: 1, start: 0, end: 1e9, color: '#FFE066' }]); return !document.querySelector('#rl-footer mark'); }));
    await page.evaluate(() => window.readle.applyHighlights([]));

    await page.evaluate(() => window.scrollTo(0, 0));
    const r0 = await page.evaluate(() => window.readle.pageBy(-1));
    check('pageBy(-1) at top reports "start"', r0 === 'start', r0);
    const r1 = await page.evaluate(() => { const y = scrollY; const r = window.readle.pageBy(1); return [r, scrollY - y, innerHeight]; });
    check('pageBy(1) scrolls ~90% of a screen', r1[0] === 'ok' && near(r1[1], r1[2] * 0.9, 2), r1);
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    const r2 = await page.evaluate(() => window.readle.pageBy(1));
    check('pageBy(1) at bottom reports "end"', r2 === 'end', r2);
    await ctx.close();
  }

  console.log('# last chapter footer and end position (end.html)');
  {
    const { ctx, page } = await openPage(browser, 'end.html', { waitMs: 1200 });
    check('fraction 1.0 scrolls to the very end', near(await scrollFrac(page), 1, 0.01), await scrollFrac(page));
    const f = await page.evaluate(() => ({ text: document.getElementById('rl-footer').textContent, buttons: Array.from(document.querySelectorAll('#rl-footer button')).map(b => b.textContent) }));
    check('last chapter says "End of book" and offers only "Previous"', /End of book/.test(f.text) && f.buttons.length === 1 && /Previous/.test(f.buttons[0]), f);
    await ctx.close();
  }

  console.log('# continuous mode: previous-chapter button at the start (frac.html / anchor.html)');
  {
    const { ctx, page } = await openPage(browser, 'frac.html', { waitMs: 1200 });
    const h = await page.evaluate(() => { const e = document.getElementById('rl-header'); return e ? { first: document.body.firstChild === e, text: e.textContent } : null; });
    check('header with Previous chapter is the first thing in the page', h && h.first && /Previous chapter/.test(h.text), h);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.click('#rl-header button');
    const nav = await calls(page, 'onNavigate');
    check('header button asks to go to the previous chapter', nav.length === 1 && nav[0][1] === 'prev', nav);
    await ctx.close();
    const b = await openPage(browser, 'anchor.html', { waitMs: 800 });
    check('no header in the first chapter', await b.page.evaluate(() => !document.getElementById('rl-header')));
    await b.ctx.close();
  }

  console.log('# paged mode (paged.html)');
  {
    const { ctx, page, consoleErrors } = await openPage(browser, 'paged.html', { waitMs: 1200 });
    const m = await page.evaluate(() => ({ iw: innerWidth, ih: innerHeight, sw: document.getElementById('rl-col').scrollWidth, sh: document.scrollingElement.scrollHeight }));
    check('content is laid out as several screen-wide pages', m.sw > m.iw * 3, m);
    check('no vertical overflow (not continuous)', m.sh <= m.ih + 1, m);
    await page.evaluate(() => window.scrollTo(0, 300));
    check('cannot be scrolled vertically', (await page.evaluate(() => scrollY)) === 0);
    const allIn = await page.evaluate(() => {
      const vp = document.getElementById('rl-vp').getBoundingClientRect(); let bad = 0, n = 0;
      document.querySelectorAll('#rl-col p, #rl-col h1').forEach(p => { for (const r of p.getClientRects()) { n++; if (r.bottom > vp.bottom + 1 || r.top < vp.top - 1) bad++; } });
      return { bad, n, vpTop: vp.top, vpBottom: vp.bottom };
    });
    check('no line of text is cut at the top or bottom on any page', allIn.bad === 0 && allIn.n > 50, allIn);
    const inside = await page.evaluate(() => {
      const rects = []; const ps = document.querySelectorAll('p,h1');
      ps.forEach(p => { for (const r of p.getClientRects()) rects.push([r.left, r.top, r.bottom, r.right]); });
      const first = rects.filter(r => r[0] >= 0 && r[0] < innerWidth);
      return { n: first.length, minTop: Math.min(...first.map(r => r[1])), maxBottom: Math.max(...first.map(r => r[2])), maxRight: Math.max(...first.map(r => r[3])), minLeft: Math.min(...first.map(r => r[0])), ih: innerHeight, iw: innerWidth };
    });
    check('text leaves space at the top', inside.minTop >= 50, inside);
    check('text leaves space at the bottom', inside.maxBottom <= inside.ih - 50, inside);
    check('text leaves side margins', inside.minLeft >= 10 && inside.maxRight <= inside.iw - 10, inside);
    check('no footer or header in paged mode', await page.evaluate(() => getComputedStyle(document.getElementById('rl-footer')).display === 'none' && !document.getElementById('rl-header') || getComputedStyle(document.getElementById('rl-header')).display === 'none'));
    const r1 = await page.evaluate(() => window.readle.pageBy(1));
    await page.waitForTimeout(400); // page turns animate
    const x1 = await page.evaluate(() => document.getElementById('rl-vp').scrollLeft);
    check('pageBy(1) moves exactly one screen to the right', r1 === 'ok' && x1 === m.iw, { r1, x1 });
    const r0 = await page.evaluate(() => { window.readle.pageBy(-1); return window.readle.pageBy(-1); });
    check('pageBy(-1) on the first page reports "start"', r0 === 'start', r0);
    const f1 = await page.evaluate(() => { window.readle.pageBy(1); window.readle.pageBy(1); return window.readle.fraction(); });
    check('fraction follows the page number', f1 > 0 && f1 < 1, f1);
    const total = await page.evaluate(() => { let n = 0; while (window.readle.pageBy(1) === 'ok') n++; return n; });
    const endRes = await page.evaluate(() => window.readle.pageBy(1));
    check('last page reports "end"', endRes === 'end' && total >= 3, { endRes, total });
    check('fraction is 1 on the last page', near(await scrollFrac(page), 1, 0.001), await scrollFrac(page));
    check('no uncaught page errors', consoleErrors.length === 0, consoleErrors);
    await page.evaluate(() => window.scrollTo(0, 0));
    await ctx.close();
  }

  console.log('# touch: taps, swipes and layout changes in paged mode (paged.html)');
  {
    const { ctx, page } = await openPage(browser, 'paged.html', { waitMs: 1200, touch: true });
    await page.touchscreen.tap(195, 400);
    await page.waitForTimeout(900);
    let taps = await calls(page, 'onTap');
    check('centre tap reports exactly one tap (touch and click are not double counted)', taps.length === 1 && near(taps[0][1], 0.5, 0.03), taps);
    await page.touchscreen.tap(360, 400);
    await page.waitForTimeout(900);
    taps = await calls(page, 'onTap');
    check('right tap reports x near 0.92', taps.length === 2 && near(taps[1][1], 360 / 390, 0.03), taps);
    await page.touchscreen.tap(30, 400);
    await page.waitForTimeout(900);
    taps = await calls(page, 'onTap');
    check('left tap reports x near 0.08', taps.length === 3 && near(taps[2][1], 30 / 390, 0.03), taps);
    const swipe = (dx) => page.evaluate((dx) => {
      const mk = (type, x) => {
        const t = new Touch({ identifier: 1, target: document.body, clientX: x, clientY: 400 });
        document.body.dispatchEvent(new TouchEvent(type, { bubbles: true, cancelable: true, touches: type === 'touchend' ? [] : [t], targetTouches: [], changedTouches: [t] }));
      };
      mk('touchstart', 200); mk('touchend', 200 + dx);
    }, dx);
    await swipe(-120);
    await page.waitForTimeout(400);
    check('swipe left goes to the next page', (await page.evaluate(() => document.getElementById('rl-vp').scrollLeft)) === 390, await page.evaluate(() => document.getElementById('rl-vp').scrollLeft));
    await swipe(120);
    await page.waitForTimeout(400);
    check('swipe right goes back one page', (await page.evaluate(() => document.getElementById('rl-vp').scrollLeft)) === 0);
    await page.waitForTimeout(100);
    const before = (await calls(page, 'onTap')).length;
    await page.touchscreen.tap(100, 300);
    await page.waitForTimeout(900);
    // tap on a link must not be reported as a page tap
    await page.evaluate(() => { document.getElementById('jump').scrollIntoView(); });
    for (let i = 0; i < 4; i++) await page.evaluate(() => window.readle.pageBy(1));
    await page.waitForTimeout(400);
    const pageNow = await page.evaluate(() => document.getElementById('rl-vp').scrollLeft / innerWidth);
    check('four forward turns reach page 4', pageNow === 4, pageNow);
    // simulate the window changing size (system bars appearing / disappearing)
    await page.setViewportSize({ width: 390, height: 700 });
    await page.waitForTimeout(300);
    const afterResize = await page.evaluate(() => ({ p: document.getElementById('rl-vp').scrollLeft / innerWidth, f: window.readle.fraction() }));
    check('page index survives a resize', afterResize.p === 4, afterResize);
    await page.evaluate(() => window.readle.pageBy(1));
    await page.waitForTimeout(400);
    const next = await page.evaluate(() => document.getElementById('rl-vp').scrollLeft / innerWidth);
    check('next turn after a resize still goes forward', next === 5, next);
    await page.setViewportSize({ width: 390, height: 800 });
    await page.waitForTimeout(300);
    await page.evaluate(() => window.readle.pageBy(1));
    await page.waitForTimeout(400);
    check('and again after resizing back', (await page.evaluate(() => document.getElementById('rl-vp').scrollLeft / innerWidth)) === 6);
    await ctx.close();
  }

  console.log('# paged mode: restore, anchor, live switch');
  {
    const e = await openPage(browser, 'paged_end.html', { waitMs: 1200 });
    check('fraction 1 opens on the last page', near(await scrollFrac(e.page), 1, 0.001), await scrollFrac(e.page));
    check('scrolled position is a whole number of pages', await e.page.evaluate(() => document.getElementById('rl-vp').scrollLeft % innerWidth === 0));
    await e.ctx.close();
    const a = await openPage(browser, 'paged_anchor.html', { waitMs: 1200 });
    const vis = await a.page.evaluate(() => { const r = document.getElementById('p40').getClientRects()[0]; return { left: r.left, iw: innerWidth }; });
    check('anchor paragraph is on the opened page', vis.left >= 0 && vis.left < vis.iw, vis);
    await a.ctx.close();
    const s = await openPage(browser, 'frac.html', { waitMs: 1500 });
    const before = await scrollFrac(s.page);
    const css = fs.readFileSync(path.join(PAGES, 'pagedcss.txt'), 'utf8');
    await s.page.evaluate(c => { const st = document.querySelector('style.rl-style'); window.readle.setStyle(st.textContent + c); }, css);
    await s.page.waitForTimeout(500);
    const mode = await s.page.evaluate(() => ({ x: document.getElementById('rl-vp').scrollLeft, y: scrollY, iw: innerWidth, f: window.readle.fraction() }));
    check('switching to pages keeps the position and snaps to a page', mode.y === 0 && mode.x % mode.iw === 0 && near(mode.f, before, 0.15), { mode, before });
    await s.ctx.close();
  }

  console.log('# page-turn animation (paged.html)');
  {
    const { ctx, page } = await openPage(browser, 'paged.html', { waitMs: 1200 });
    await page.evaluate(() => window.readle.pageBy(1));
    await page.waitForTimeout(60);
    const mid = await page.evaluate(() => ({ x: document.getElementById('rl-vp').scrollLeft, iw: innerWidth }));
    check('mid-turn the page is between two pages (it slides)', mid.x > 0 && mid.x < mid.iw, mid);
    await page.waitForTimeout(400);
    check('it ends exactly on the next page', (await page.evaluate(() => document.getElementById('rl-vp').scrollLeft)) === mid.iw);
    await page.evaluate(() => { window.readle.pageBy(1); window.readle.pageBy(1); });
    await page.waitForTimeout(500);
    check('rapid turns end on the right page', (await page.evaluate(() => document.getElementById('rl-vp').scrollLeft)) === mid.iw * 3);
    await page.evaluate(() => document.documentElement.style.setProperty('--rl-anim', '0'));
    await page.evaluate(() => window.readle.pageBy(1));
    const instant = await page.evaluate(() => document.getElementById('rl-vp').scrollLeft);
    await page.waitForTimeout(400);
    const pc = await calls(page, 'onPages');
    const lastPc = pc[pc.length - 1];
    check('page counts are reported (page 5 of N)', !!lastPc && lastPc[1] === 5 && lastPc[2] > 5, lastPc);
    check('Reduce motion (--rl-anim:0) turns instantly', instant === mid.iw * 4, instant);
    await ctx.close();
  }

  console.log('# read aloud: speech text, highlight, visible position (paged.html and frac.html)');
  {
    const { ctx, page } = await openPage(browser, 'paged.html', { waitMs: 1200 });
    const first = JSON.parse(await page.evaluate(() => window.readle.ttsPrepare(-1)));
    check('speech text is built from the page', first.t.length > 1000, first.t.length);
    check('speech text has a newline between paragraphs', first.t.includes('\n'));
    check('first page starts reading at the very beginning', first.o === 0, first.o);
    await page.evaluate(() => window.readle.pageBy(1));
    await page.waitForTimeout(400);
    const second = JSON.parse(await page.evaluate(() => window.readle.ttsPrepare(-1)));
    check('on page 2 reading starts at the first word of that page', second.o > 100 && second.o < second.t.length, second.o);
    // the character at offset o must be the first one on page 2: mark it and check where it is
    const pos = await page.evaluate(o => { window.readle.ttsMark(o, o + 1); const sel = document.querySelector('mark.rl-tts'); 
      const hl = window.CSS && CSS.highlights && CSS.highlights.get('rl-tts'); let rc;
      if (hl) { rc = [...hl][0].getClientRects()[0]; } else { rc = sel.getClientRects()[0]; }
      return { left: rc.left, iw: innerWidth, native: !!hl }; }, second.o);
    check('that first word is on the current page', pos.left >= -1 && pos.left < pos.iw / 2, pos);
    await page.evaluate(o => window.readle.ttsMark(o - 3, o - 1), second.o);
    await page.waitForTimeout(400);
    check('marking a word on the previous page turns back to it', (await page.evaluate(() => document.getElementById('rl-vp').scrollLeft)) === 0);
    // marking a word on a later page turns exactly to that page, not earlier
    const far = first.t.indexOf('\n', Math.floor(first.t.length * 0.6)) + 1;
    await page.evaluate(o => window.readle.ttsMark(o, o + 5), far);
    await page.waitForTimeout(400);
    const pgNow = await page.evaluate(() => { const hl = window.CSS && CSS.highlights && CSS.highlights.get('rl-tts'); const rc = hl ? [...hl][0].getClientRects()[0] : document.querySelector('mark.rl-tts').getClientRects()[0]; return rc.left; });
    check('the marked word is on screen after the page turn', pgNow >= -1 && pgNow < 390, pgNow);
    const vis = JSON.parse(await page.evaluate(o => window.readle.ttsPrepare(o), far));
    check('lastVisible is true for the word on screen', vis.v === true, vis.v);
    const gone = JSON.parse(await page.evaluate(() => window.readle.ttsPrepare(1)));
    check('lastVisible is false for a word on another page', gone.v === false, gone.v);
    await page.evaluate(() => window.readle.ttsClear());
    check('clearing removes the highlight', await page.evaluate(() => { const h = window.CSS && CSS.highlights && CSS.highlights.get('rl-tts'); return h ? h.size === 0 : !document.querySelector('mark.rl-tts'); }));
    await ctx.close();

    const c = await openPage(browser, 'frac.html', { waitMs: 1500 });
    const cp = JSON.parse(await c.page.evaluate(() => window.readle.ttsPrepare(-1)));
    check('continuous mode: reading starts near the visible top, not at the start', cp.o > 500, cp.o);
    await c.page.evaluate(() => window.scrollTo(0, 0));
    const top = JSON.parse(await c.page.evaluate(() => window.readle.ttsPrepare(-1)));
    check('continuous mode at the top: starts at 0', top.o === 0, top.o);
    const farOff = Math.floor(top.t.length * 0.9);
    await c.page.evaluate(o => window.readle.ttsMark(o, o + 4), farOff);
    await c.page.waitForTimeout(200);
    const y = await c.page.evaluate(() => scrollY);
    check('marking a word far down scrolls it into view', y > 1000, y);
    await c.ctx.close();
  }

  console.log('# live style change keeps position');
  {
    const { ctx, page } = await openPage(browser, 'frac.html', { waitMs: 1500 });
    const before = await scrollFrac(page);
    await page.evaluate(() => window.readle.setStyle('html{font-size:200% !important}body{background:#000 !important;color:#fff !important;margin:0 !important;padding:12px 16px 24px !important}'));
    await page.waitForTimeout(300);
    const after = await scrollFrac(page);
    const size = await page.evaluate(() => getComputedStyle(document.body).fontSize);
    check('style applied live (32px at 200%)', size === '32px', size);
    check('reading position preserved across the style change', near(before, after, 0.03), { before, after });
    await ctx.close();
  }

  await browser.close();
  console.log(`\n${passes} passed, ${failures} failed`);
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
