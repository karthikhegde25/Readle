package com.readle.app.reader.reflow

/**
 * The JavaScript injected into every chapter. It is the only script that runs in the page: the CSP sent
 * with each chapter allows scripts only with Readle's per-load nonce, so scripts inside a book are blocked.
 *
 * Text positions are character offsets into the concatenation of the chapter's text nodes (ignoring
 * script/style and Readle's own UI), which makes highlights independent of font size and layout.
 *
 * NOTE: this is a Kotlin raw string, so the JavaScript must not contain the dollar sign.
 */
object ReaderScript {
    const val JS = """
(function () {
  'use strict';
  var B = window.ReadleBridge;
  var cfg = window.__readleInit || {};
  var userMoved = false;
  var armed = false;
  var reportTimer = null;

  function root() { return document.scrollingElement || document.documentElement; }
  function maxScroll() { return Math.max(1, root().scrollHeight - window.innerHeight); }

  // ---- paged mode -------------------------------------------------------------------------
  // In pages mode the chapter's content is moved into a fixed-size box (#rl-vp) whose inner element (#rl-col)
  // is laid out in CSS columns with explicit pixel sizes: one column = one screen-wide page, with blank space
  // above and below. Pages are shown by scrolling the box sideways. The document itself never scrolls.
  var pg = null;      // { vp, col, pad } while paged
  var curPg = 0;      // current page, remembered explicitly (never re-derived from a scroll offset)

  function isPaged() {
    try { return getComputedStyle(document.documentElement).getPropertyValue('--rl-paged').trim() === '1'; } catch (e) { return false; }
  }
  function cssNum(name, dflt) {
    try {
      var v = parseFloat(getComputedStyle(document.documentElement).getPropertyValue(name));
      return isNaN(v) ? dflt : v;
    } catch (e) { return dflt; }
  }
  function viewW() { return Math.max(1, document.documentElement.clientWidth || window.innerWidth); }
  function viewH() { return Math.max(1, window.innerHeight || document.documentElement.clientHeight); }

  function enablePaged() {
    if (!document.body) return;
    if (!pg) {
      var vp = document.createElement('div'); vp.id = 'rl-vp';
      var col = document.createElement('div'); col.id = 'rl-col';
      var pad = document.createElement('div'); pad.id = 'rl-pad';
      var kids = Array.prototype.slice.call(document.body.childNodes);
      for (var i = 0; i < kids.length; i++) {
        var k = kids[i];
        if (k.nodeType === 1 && (k.tagName === 'SCRIPT' || k.id === 'rl-header' || k.id === 'rl-footer')) continue;
        col.appendChild(k);
      }
      vp.appendChild(col); vp.appendChild(pad);
      document.body.appendChild(vp);
      pg = { vp: vp, col: col, pad: pad };
    }
    layoutPaged();
  }

  function disablePaged() {
    if (!pg) return;
    var footer = document.getElementById('rl-footer');
    while (pg.col.firstChild) {
      var n = pg.col.firstChild;
      if (footer && footer.parentNode === document.body) document.body.insertBefore(n, footer); else document.body.appendChild(n);
    }
    if (pg.vp.parentNode) pg.vp.parentNode.removeChild(pg.vp);
    pg = null;
    document.documentElement.style.removeProperty('--rl-h');
  }

  function layoutPaged() {
    if (!pg) return;
    var W = viewW(), H = viewH();
    var m = cssNum('--rl-m', 16), top = cssNum('--rl-pt', 56), bottom = cssNum('--rl-pb', 56);
    var h = Math.max(80, H - top - bottom);
    document.documentElement.style.setProperty('--rl-h', h + 'px');
    var vs = pg.vp.style;
    vs.position = 'fixed'; vs.left = '0px'; vs.top = top + 'px'; vs.width = W + 'px'; vs.height = h + 'px'; vs.overflow = 'hidden';
    var cs = pg.col.style;
    cs.boxSizing = 'border-box'; cs.width = W + 'px'; cs.height = h + 'px'; cs.padding = '0 ' + m + 'px'; cs.margin = '0';
    cs.columnWidth = (W - 2 * m) + 'px'; cs.columnGap = (2 * m) + 'px'; cs.columnFill = 'auto'; cs.overflow = 'visible';
    var n = pageCount();
    pg.pad.style.cssText = 'position:absolute;left:0;top:0;height:1px;width:' + (n * W) + 'px;visibility:hidden;pointer-events:none;';
    if (curPg > n - 1) curPg = n - 1;
    cancelAnim();
    pg.vp.scrollLeft = curPg * W;
  }

  // Switches between the two layouts to match the current stylesheet.
  function applyMode() {
    if (isPaged()) { if (!pg) enablePaged(); else layoutPaged(); }
    else if (pg) disablePaged();
  }

  function pageCount() {
    if (!pg) return 1;
    return Math.max(1, Math.ceil((pg.col.scrollWidth - 2) / viewW()));
  }
  function curPage() { return curPg; }
  // Page-turn animation: slides the box sideways over a short time. Off when --rl-anim is 0 (Reduce motion).
  var anim = null;
  function cancelAnim() { if (anim) { cancelAnimationFrame(anim.raf); anim = null; } }
  function animateTo(target) {
    cancelAnim();
    var vp = pg.vp, from = vp.scrollLeft, dist = target - from;
    if (Math.abs(dist) < 2) { vp.scrollLeft = target; return; }
    var t0 = null, dur = 240, a = {};
    anim = a;
    function step(ts) {
      if (anim !== a) return;
      if (t0 === null) t0 = ts;
      var k = Math.min(1, (ts - t0) / dur), e = 1 - Math.pow(1 - k, 3);
      vp.scrollLeft = from + dist * e;
      if (k < 1) a.raf = requestAnimationFrame(step); else { anim = null; vp.scrollLeft = target; }
    }
    a.raf = requestAnimationFrame(step);
  }
  function goPage(p, animate) {
    if (!pg) return;
    var n = pageCount();
    curPg = Math.min(n - 1, Math.max(0, p));
    var target = curPg * viewW();
    if (animate && cssNum('--rl-anim', 1) > 0) animateTo(target);
    else { cancelAnim(); pg.vp.scrollLeft = target; }
  }
  function resync() {
    if (!pg || anim) return;
    var n = pageCount();
    if (curPg > n - 1) curPg = n - 1;
    var want = curPg * viewW();
    if (Math.abs(pg.vp.scrollLeft - want) > 1) pg.vp.scrollLeft = want;
  }
  function pageOfRect(rect) {
    if (!pg) return 0;
    return Math.floor((rect.left + pg.vp.scrollLeft + 1) / viewW());
  }
  function fraction() {
    if (pg) {
      var n = pageCount();
      return n <= 1 ? 0 : Math.min(1, Math.max(0, curPg / (n - 1)));
    }
    return Math.min(1, Math.max(0, window.scrollY / maxScroll()));
  }
  function showFraction(f) {
    f = Math.min(1, Math.max(0, f));
    if (pg) goPage(Math.round(f * (pageCount() - 1)));
    else window.scrollTo(0, f * maxScroll());
  }
  function showRect(rect) {
    if (pg) goPage(pageOfRect(rect));
    else window.scrollTo(0, Math.max(0, window.scrollY + rect.top - window.innerHeight * 0.3));
  }
  // The box scrolls programmatically only; if anything moves it, put it back on the current page.
  document.addEventListener('scroll', function (e) { if (pg && e.target === pg.vp) { resync(); scheduleReport(); } }, true);
  // Keep the page from being dragged around by the finger.
  // Paged layouts are turned by the script, so the browser's own scrolling is blocked. Document formats that allow
  // zoom still let a pinch, and dragging around while zoomed in, through to the browser.
  function zoomedIn() { return !!(cfg.zoomable && window.visualViewport && window.visualViewport.scale > 1.05); }
  document.addEventListener('touchmove', function (e) {
    if (pg && e.cancelable && !(cfg.zoomable && (e.touches.length > 1 || zoomedIn()))) e.preventDefault();
  }, { passive: false });

  function report() {
    reportTimer = null;
    if (!armed && !userMoved) return;
    try { B.onScroll(fraction(), window.scrollY, root().scrollHeight, window.innerHeight); } catch (e) {}
    try {
      var total, at;
      if (pg) { total = pageCount(); at = curPg + 1; }
      else { var vh = viewH(); total = Math.max(1, Math.ceil(root().scrollHeight / vh)); at = Math.min(total, Math.floor(window.scrollY / vh) + 1); }
      B.onPages(at, total);
    } catch (e) {}
  }
  function scheduleReport() { if (reportTimer === null) reportTimer = setTimeout(report, 150); }
  window.addEventListener('scroll', scheduleReport, { passive: true });
  ['touchstart', 'wheel', 'keydown', 'mousedown'].forEach(function (ev) {
    window.addEventListener(ev, function () { userMoved = true; }, { passive: true });
  });

  var ttsDirty = true;

  // ---- text model -------------------------------------------------------------------------
  function textNodes() {
    var out = [];
    if (!document.body) return out;
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = w.nextNode())) {
      var p = n.parentElement;
      if (!p) continue;
      if (p.closest('script,style,noscript,.rl-ui')) continue;
      out.push(n);
    }
    return out;
  }

  function offsetOf(container, off) {
    var b = document.createRange();
    b.setStart(container, off);
    b.collapse(true);
    var nodes = textNodes(), total = 0;
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n === container) { total += off; return total; }
      if (b.comparePoint(n, 0) < 0) total += n.nodeValue.length; else break;
    }
    return total;
  }

  function segmentsFor(start, end) {
    var nodes = textNodes(), pos = 0, segs = [];
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i], len = n.nodeValue.length, ns = pos, ne = pos + len;
      if (ne > start && ns < end) {
        segs.push({ node: n, s: Math.max(start, ns) - ns, e: Math.min(end, ne) - ns });
      }
      pos = ne;
      if (pos >= end) break;
    }
    return segs;
  }

  function rangeFor(start, end) {
    var segs = segmentsFor(start, end);
    if (!segs.length) return null;
    var r = document.createRange();
    r.setStart(segs[0].node, segs[0].s);
    var last = segs[segs.length - 1];
    r.setEnd(last.node, last.e);
    return r;
  }

  function unwrapAll(selector) {
    ttsDirty = true;
    var marks = document.querySelectorAll(selector);
    for (var i = 0; i < marks.length; i++) {
      var m = marks[i], parent = m.parentNode;
      if (!parent) continue;
      while (m.firstChild) parent.insertBefore(m.firstChild, m);
      parent.removeChild(m);
      parent.normalize();
    }
  }

  function wrapRange(start, end, className, color, id, dotted) {
    ttsDirty = true;
    var segs = segmentsFor(start, end);
    for (var i = segs.length - 1; i >= 0; i--) {
      var sg = segs[i];
      var r = document.createRange();
      r.setStart(sg.node, sg.s);
      r.setEnd(sg.node, sg.e);
      var m = document.createElement('mark');
      m.className = className;
      if (id !== null) m.setAttribute('data-id', String(id));
      if (color) m.style.backgroundColor = color;
      if (dotted) m.style.borderBottom = '2px dotted currentColor';
      try { r.surroundContents(m); } catch (e) {}
    }
  }

  function applyHighlights(list) {
    unwrapAll('mark.rl-hl');
    var items = (list || []).slice().sort(function (a, b) { return b.start - a.start; });
    items.forEach(function (h) { wrapRange(h.start, h.end, 'rl-hl', h.color, h.id, !!h.note); });
  }

  function scrollToOffset(off) {
    var r = rangeFor(off, off + 1);
    if (!r) return false;
    showRect(r.getBoundingClientRect());
    return true;
  }

  // ---- find in chapter --------------------------------------------------------------------
  function normalizedText() {
    var nodes = textNodes(), out = [], map = [], pos = 0, prevSpace = true;
    for (var i = 0; i < nodes.length; i++) {
      var v = nodes[i].nodeValue;
      for (var k = 0; k < v.length; k++) {
        var c = v.charAt(k);
        if (/\s/.test(c)) {
          if (!prevSpace) { out.push(' '); map.push(pos); prevSpace = true; }
        } else {
          var lc = c.toLowerCase();
          out.push(lc.length === 1 ? lc : c);
          map.push(pos);
          prevSpace = false;
        }
        pos++;
      }
    }
    return { text: out.join(''), map: map };
  }

  function clearFind() { unwrapAll('mark.rl-find'); }

  function findText(query, nth) {
    clearFind();
    var qn = String(query).toLowerCase().replace(/\s+/g, ' ').trim();
    if (!qn) return false;
    var nt = normalizedText();
    var idx = -1, from = 0;
    for (var i = 0; i <= nth; i++) {
      idx = nt.text.indexOf(qn, from);
      if (idx < 0) return false;
      from = idx + qn.length;
    }
    var s = nt.map[idx], e = nt.map[idx + qn.length - 1] + 1;
    wrapRange(s, e, 'rl-find', null, null, false);
    var first = document.querySelector('mark.rl-find');
    if (first) {
      showRect(first.getBoundingClientRect());
    }
    userMoved = true;
    return true;
  }

  // ---- selection --------------------------------------------------------------------------
  var selTimer = null;
  document.addEventListener('selectionchange', function () {
    if (selTimer) clearTimeout(selTimer);
    selTimer = setTimeout(function () {
      var sel = window.getSelection();
      if (!sel || sel.isCollapsed || sel.rangeCount === 0) { try { B.onSelectionCleared(); } catch (e) {} return; }
      var text = sel.toString();
      if (!text || !text.trim()) { try { B.onSelectionCleared(); } catch (e) {} return; }
      var r = sel.getRangeAt(0);
      var s = offsetOf(r.startContainer, r.startOffset);
      var e = offsetOf(r.endContainer, r.endOffset);
      if (e < s) { var t = s; s = e; e = t; }
      if (text.length > 5000) text = text.substring(0, 5000);
      try { B.onSelection(text, s, e); } catch (err) {}
    }, 250);
  });

  function clearSelection() {
    var s = window.getSelection();
    if (s) s.removeAllRanges();
  }

  // ---- taps and links ---------------------------------------------------------------------
  document.addEventListener('click', function (ev) {
    var t = ev.target;
    if (!t || !t.closest) return;
    var mark = t.closest('mark.rl-hl');
    if (mark) { try { B.onHighlightTap(parseInt(mark.getAttribute('data-id'), 10) || 0); } catch (e) {} return; }
    var a = t.closest('a[href]');
    if (a) {
      var href = a.getAttribute('href') || '';
      if (href.charAt(0) === '#') { try { B.onInternalJump(fraction()); } catch (e) {} }
      return;
    }
    if (t.closest('.rl-ui')) return;
    if (Date.now() - lastTapAt < 700) return; // the touch handler already reported this tap
    var sel = window.getSelection();
    if (sel && !sel.isCollapsed) return;
    reportTap(ev.clientX);
  }, false);

  // ---- paging and scrolling ---------------------------------------------------------------
  function pageBy(dir) {
    userMoved = true;
    if (pg) {
      var p = curPage(), n = pageCount();
      if (dir > 0 && p >= n - 1) return 'end';
      if (dir < 0 && p <= 0) return 'start';
      goPage(p + (dir > 0 ? 1 : -1), true);
      return 'ok';
    }
    var before = window.scrollY, max = maxScroll();
    if (dir > 0 && before >= max - 2) return 'end';
    if (dir < 0 && before <= 2) return 'start';
    window.scrollTo(0, Math.min(max, Math.max(0, before + dir * window.innerHeight * 0.9)));
    return 'ok';
  }

  // Touch handling: a quick, small touch is a tap; a quick horizontal drag in paged mode is a swipe.
  // Taps are detected from touch events (WebView's synthetic click is not reliable), and the click
  // handler below only acts as a fallback for non-touch input; lastTapAt keeps the two from double-firing.
  var ts = null, lastTapAt = 0;
  function reportTap(clientX) {
    var w = document.documentElement.clientWidth || window.innerWidth || 1;
    try { B.onTap(Math.min(1, Math.max(0, clientX / Math.max(1, w)))); } catch (e) {}
  }
  document.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) { ts = null; return; }
    var t = e.touches[0];
    var r0 = root();
    ts = { x: t.clientX, y: t.clientY, t: Date.now(), target: e.target,
           atTop: r0.scrollTop <= 1, atBottom: r0.scrollTop + r0.clientHeight >= r0.scrollHeight - 2 };
  }, { passive: true });
  document.addEventListener('touchcancel', function () { ts = null; }, { passive: true });
  document.addEventListener('touchend', function (e) {
    var s0 = ts; ts = null;
    if (!s0 || !e.changedTouches.length) return;
    var ct = e.changedTouches[0];
    var dx = ct.clientX - s0.x, dy = ct.clientY - s0.y, dt = Date.now() - s0.t;
    var sel = window.getSelection();
    var hasSel = !!(sel && !sel.isCollapsed);
    if (pg && !zoomedIn() && !hasSel && dt < 700 && Math.abs(dx) >= 50 && Math.abs(dx) >= Math.abs(dy) * 1.5) {
      var res = pageBy(dx < 0 ? 1 : -1);
      if (res === 'end' && !cfg.last) { try { B.onNavigate('next'); } catch (err) {} }
      else if (res === 'start' && !cfg.first) { try { B.onNavigate('prev'); } catch (err) {} }
      lastTapAt = Date.now();
      return;
    }
    // Scrolling mode: a firm drag past the end of a chapter goes on to the next one, past the start to the previous one.
    if (cfg.zoomable && !pg && !hasSel && dt < 900 && Math.abs(dy) >= 110 && Math.abs(dy) >= Math.abs(dx) * 1.5 &&
        (!window.visualViewport || window.visualViewport.scale < 1.05)) {
      var r1 = root();
      var endNow = r1.scrollTop + r1.clientHeight >= r1.scrollHeight - 2;
      var topNow = r1.scrollTop <= 1;
      if (dy < 0 && s0.atBottom && endNow && !cfg.last) { try { B.onNavigate('next'); } catch (err) {} lastTapAt = Date.now(); return; }
      if (dy > 0 && s0.atTop && topNow && !cfg.first) { try { B.onNavigate('prev'); } catch (err) {} lastTapAt = Date.now(); return; }
    }
    if (Math.abs(dx) > 14 || Math.abs(dy) > 14 || dt > 600) return;
    var t = s0.target;
    if (t && t.closest && t.closest('a[href],mark.rl-hl,.rl-ui,button')) return;
    if (hasSel) { clearSelection(); lastTapAt = Date.now(); return; }
    lastTapAt = Date.now();
    reportTap(ct.clientX);
  }, { passive: true });
  window.addEventListener('resize', function () { applyMode(); resync(); });

  function scrollToFraction(f) {
    userMoved = true;
    showFraction(f);
  }

  function setStyle(css) {
    var f = fraction();
    var els = document.querySelectorAll('style.rl-style');
    for (var i = 0; i < els.length; i++) els[i].textContent = css;
    userMoved = true;
    requestAnimationFrame(function () { applyMode(); showFraction(f); });
    setTimeout(function () { applyMode(); showFraction(f); }, 150);
  }

  // ---- chapter footer ---------------------------------------------------------------------
  function navButton(label, dir) {
    var b = document.createElement('button');
    b.type = 'button';
    b.textContent = label;
    b.addEventListener('click', function () { try { B.onNavigate(dir); } catch (e) {} });
    return b;
  }

  function addHeader() {
    if (!document.body || cfg.first || document.getElementById('rl-header')) return;
    var h = document.createElement('div');
    h.id = 'rl-header';
    h.className = 'rl-ui';
    h.appendChild(navButton('← Previous chapter', 'prev'));
    document.body.insertBefore(h, document.body.firstChild);
  }

  function addFooter() {
    if (!document.body || document.getElementById('rl-footer')) return;
    var f = document.createElement('div');
    f.id = 'rl-footer';
    f.className = 'rl-ui';
    if (!cfg.first) f.appendChild(navButton('← Previous chapter', 'prev'));
    if (!cfg.last) {
      f.appendChild(navButton('Next chapter →', 'next'));
    } else {
      var end = document.createElement('div');
      end.textContent = 'End of book';
      f.appendChild(end);
    }
    document.body.appendChild(f);
  }

  // ---- restore position -------------------------------------------------------------------
  function restore() {
    if (cfg.offset !== null && cfg.offset !== undefined) { if (scrollToOffset(cfg.offset)) return; }
    if (cfg.anchor) {
      var el = document.getElementById(cfg.anchor) || document.getElementsByName(cfg.anchor)[0];
      if (el) {
        if (pg) goPage(pageOfRect(el.getBoundingClientRect())); else el.scrollIntoView();
        return;
      }
    }
    if (cfg.fraction !== null && cfg.fraction !== undefined) showFraction(cfg.fraction);
  }

  function init() {
    addHeader();
    addFooter();
    applyMode();
    applyHighlights(cfg.highlights || []);
    restore();
    try { B.onReady(); } catch (e) {}
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();

  window.addEventListener('load', function () {
    applyMode();
    if (!userMoved) restore();
    setTimeout(function () { armed = true; report(); }, 2500);
  });
  var tries = 0;
  var iv = setInterval(function () {
    if (userMoved || ++tries > 8) { clearInterval(iv); return; }
    restore();
  }, 300);


  // ---- read aloud: speech text, word highlight, position on screen ---------------------------
  // The speech text is built from the page itself (so it always matches what is on screen), with a newline
  // inserted wherever a new block starts. Offsets used by Readle's TTS are offsets into that text.
  var BLOCKS = 'p,div,h1,h2,h3,h4,h5,h6,li,tr,td,th,blockquote,section,article,pre,figcaption,dd,dt,header,footer,aside,table';
  var tts = null, ttsHL = null;

  function blockOf(n) {
    var p = n.parentElement, b = p ? p.closest(BLOCKS) : null;
    return (b && b.id !== 'rl-col' && b.id !== 'rl-vp') ? b : null;
  }

  function buildTts() {
    var nodes = textNodes(), segs = [], parts = [], pos = 0, prev = null, endsNl = true, first = true;
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i], v = n.nodeValue;
      if (!/\S/.test(v)) continue;
      var b = blockOf(n);
      var br = !!(n.previousSibling && n.previousSibling.nodeName === 'BR');
      if (!first && (b !== prev || br) && !endsNl) { parts.push('\n'); pos += 1; endsNl = true; }
      var flat = v.replace(/[\r\n\t]/g, ' ');
      segs.push({ n: n, s: pos, len: v.length });
      parts.push(flat);
      pos += v.length;
      endsNl = false; first = false; prev = b;
    }
    tts = { text: parts.join(''), segs: segs };
    ttsDirty = false;
    return tts;
  }

  function ttsSegAt(off) {
    var a = tts.segs, lo = 0, hi = a.length - 1, best = -1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (a[mid].s <= off) { best = mid; lo = mid + 1; } else hi = mid - 1;
    }
    return best;
  }

  function ttsRange(s, e) {
    if (!tts || ttsDirty) buildTts();
    if (!tts.segs.length || s < 0 || e <= s) return null;
    var i = ttsSegAt(s);
    if (i < 0) i = 0;
    var a = tts.segs[i];
    var so = s - a.s;
    if (so >= a.len) { if (i + 1 >= tts.segs.length) return null; a = tts.segs[i + 1]; so = 0; }
    var j = ttsSegAt(e - 1);
    if (j < 0) return null;
    var z = tts.segs[j];
    var eo = Math.min(z.len, e - z.s);
    if (eo <= 0) { if (j === 0) return null; z = tts.segs[j - 1]; eo = z.len; }
    var r = document.createRange();
    try { r.setStart(a.n, so); r.setEnd(z.n, eo); } catch (err) { return null; }
    return r;
  }

  function ttsPaint(r) {
    try {
      if (window.CSS && CSS.highlights && window.Highlight) {
        if (!ttsHL) ttsHL = new Highlight();
        ttsHL.clear(); ttsHL.add(r);
        CSS.highlights.set('rl-tts', ttsHL);
        return;
      }
    } catch (err) {}
    unwrapAll('mark.rl-tts');
    var st = offsetOf(r.startContainer, r.startOffset), en = offsetOf(r.endContainer, r.endOffset);
    wrapRange(st, en, 'rl-tts', null, null, false);
  }

  function ttsClear() {
    try { if (window.CSS && CSS.highlights) CSS.highlights.delete('rl-tts'); } catch (err) {}
    if (ttsHL) { try { ttsHL.clear(); } catch (err) {} }
    unwrapAll('mark.rl-tts');
  }

  function rectIsOnScreen(rc) {
    return pg ? (rc.left >= -1 && rc.left < viewW()) : (rc.bottom > 0 && rc.top < viewH());
  }

  // Highlights the word being spoken and keeps it on screen: pages turn only when the word is on the next page.
  function ttsMark(s, e) {
    var r = ttsRange(s, Math.max(e, s + 1));
    if (!r) return false;
    ttsPaint(r);
    var rects = r.getClientRects(), rc = rects.length ? rects[0] : r.getBoundingClientRect();
    if (pg) {
      var p = pageOfRect(rc);
      if (p !== curPg) goPage(p, true);
    } else {
      var H = viewH();
      if (rc.top < H * 0.08 || rc.bottom > H * 0.85) window.scrollTo(0, Math.max(0, window.scrollY + rc.top - H * 0.25));
    }
    return true;
  }

  function charRect(node, i, len) {
    var g = document.createRange();
    for (var k = i; k < len && k < i + 40; k++) {
      g.setStart(node, k); g.setEnd(node, k + 1);
      var rs = g.getClientRects();
      if (rs.length) return rs[0];
    }
    return null;
  }

  function startsOnOrAfterTop(rc) { return pg ? rc.left >= -1 : rc.top >= -2; }

  // Offset (in the speech text) of the first character that is fully on the current page / screen.
  function ttsFirstVisible() {
    var a = tts.segs;
    if (!a.length) return 0;
    var lo = 0, hi = a.length - 1, k = a.length - 1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1, sg = a[mid];
      var rc = charRect(sg.n, Math.max(0, sg.len - 1), sg.len);
      if (rc && startsOnOrAfterTop(rc)) { k = mid; hi = mid - 1; } else lo = mid + 1;
    }
    var seg = a[k], l2 = 0, h2 = seg.len - 1, at = 0;
    while (l2 <= h2) {
      var m2 = (l2 + h2) >> 1;
      var r2 = charRect(seg.n, m2, seg.len);
      if (r2 && startsOnOrAfterTop(r2)) { at = m2; h2 = m2 - 1; } else l2 = m2 + 1;
    }
    if (l2 > seg.len - 1) at = 0;
    return seg.s + at;
  }

  // Returns JSON { t: speech text, o: where the visible page starts, v: is the word at lastOff on screen }.
  function ttsPrepare(lastOff) {
    buildTts();
    var res = { t: tts.text, o: ttsFirstVisible(), v: false };
    if (typeof lastOff === 'number' && lastOff >= 0) {
      var r = ttsRange(lastOff, lastOff + 1);
      if (r) {
        var rects = r.getClientRects();
        if (rects.length) res.v = rectIsOnScreen(rects[0]);
      }
    }
    return JSON.stringify(res);
  }

  window.readle = {
    pageBy: pageBy,
    scrollToFraction: scrollToFraction,
    setStyle: setStyle,
    applyHighlights: applyHighlights,
    scrollToOffset: scrollToOffset,
    findText: findText,
    clearFind: clearFind,
    clearSelection: clearSelection,
    fraction: fraction,
    ttsPrepare: ttsPrepare,
    ttsMark: ttsMark,
    ttsClear: ttsClear
  };
})();
"""
}
