// Runs inside instagram.com in ScrollStop's browser. ScrollStop puts `var SS_CFG = {...};` in front.
(function () {
  if (window.__scrollstop) { window.__scrollstop.run(); return; }
  var C = (typeof SS_CFG !== 'undefined') ? SS_CFG : {};
  var api = window.__scrollstop = {};

  var navSel = [];
  if (C.reels) navSel.push('a[href="/reels/"]', 'a[href^="/reels/?"]', 'a[href="/reels"]');
  if (C.explore) navSel.push('a[href="/explore/"]', 'a[href="/explore"]');
  var css = navSel.length ? navSel.join(',') + '{display:none!important}' : '';
  if (C.home) css += 'html.ss-home main{visibility:hidden!important}' +
    'html.ss-home body::after{content:"Your feed is hidden. Check your messages or search for someone.";' +
    'position:fixed;left:24px;right:24px;top:40%;text-align:center;font:16px sans-serif;color:#888;}';

  function tell(kind) { try { ScrollStop.hidden(kind); } catch (e) {} }

  function addStyle() {
    if (!css || document.getElementById('ss-style')) return;
    var s = document.createElement('style');
    s.id = 'ss-style';
    s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
  }

  // Remove the whole slot the Reels / Explore button sat in, so the other buttons close the gap.
  function collapse(a) {
    var n = a;
    for (var i = 0; i < 4; i++) {
      var p = n.parentElement;
      if (!p || p === document.body || p.tagName === 'NAV' || p.children.length !== 1) break;
      var r = p.getBoundingClientRect();
      if (r.height > 140 || r.width > 260) break;
      n = p;
    }
    if (n !== a) n.style.setProperty('display', 'none', 'important');
  }

  // ---- ads & suggested posts ----
  // "Sponsored" in the languages Instagram shows most often, plus branded "Paid partnership" posts.
  var AD_LINES = ['Sponsored', 'Sponsorisé', 'Sponsorisée', 'Gesponsert', 'Patrocinado', 'Patrocinada',
    'Publicidad', 'Sponsorizzato', 'Gesponsord', 'Sponsorowane', 'Реклама', 'Sponsorlu', 'Sponzorováno',
    'प्रायोजित', 'प्रायोजित पोस्ट', 'স্পনসর করা', 'ஸ்பான்சர் செய்யப்பட்டது', 'ప్రాయోజితం', 'スポンサー', '광고',
    '赞助内容', '贊助', 'Được tài trợ', 'Bersponsor', 'Disponsori', 'ممول', 'Χορηγούμενη', 'Sponsrad', 'Sponsoreret'];
  var AD_STARTS = ['Paid partnership', 'Sponsored ·', 'Sponsored •'];
  var SUGGESTED_LINES = ['Suggested for you', 'Suggested posts', 'Suggested post', 'Suggested reels',
    'Suggested threads', 'Suggested', 'Recommended for you', 'Popular on Instagram', 'Based on your activity',
    'Posts you might like', 'More posts you might like', 'Suggestions for you', 'सुझाए गए पोस्ट', 'आपके लिए सुझाव'];
  var SUGGESTED_STARTS = ['Because you liked', 'Because you follow', 'Because you watched', 'Based on your'];
  var CAUGHT_UP = /You(’|')(re|ve) (all|completely) caught up/;

  function lines(el) {
    return (el.innerText || '').split('\n').map(function (l) { return l.trim(); }).filter(Boolean);
  }
  function hasLine(ls, exact, starts) {
    for (var i = 0; i < ls.length; i++) {
      if (exact.indexOf(ls[i]) >= 0) return true;
      for (var j = 0; j < starts.length; j++) if (ls[i].indexOf(starts[j]) === 0) return true;
    }
    return false;
  }
  function onFeed() { return location.pathname === '/'; }

  // On the home feed, a post with a "Follow" button in its header is from an account you don't follow.
  function followInHeader(a) {
    var top = a.getBoundingClientRect().top;
    var scope = a.querySelector('header') || a;
    var els = scope.querySelectorAll('button, [role="button"], div, span');
    for (var i = 0; i < els.length && i < 400; i++) {
      var el = els[i];
      if (el.children.length > 1) continue;
      var t = (el.textContent || '').trim();
      if ((t === 'Follow' || t === 'Follow back') && el.getBoundingClientRect().top - top < 110) return true;
    }
    return false;
  }

  function hide(el, kind) {
    el.setAttribute('data-ss', kind);
    el.style.setProperty('display', 'none', 'important');
    tell(kind);
  }

  // Everything after "You're all caught up" on the home feed is Instagram's suggestions.
  function afterCaughtUp() {
    var spans = document.querySelectorAll('main span, main div[role="heading"], main h2, main h3');
    for (var i = 0; i < spans.length; i++) {
      if (spans[i].children.length > 2 || !CAUGHT_UP.test(spans[i].textContent || '')) continue;
      return spans[i];
    }
    return null;
  }

  // Rows like "Suggested for you · See all" with account cards, which aren't posts.
  function hideSuggestionRows() {
    var heads = document.querySelectorAll('main span, main div[role="heading"], main h2, main h3, main h4');
    for (var i = 0; i < heads.length; i++) {
      var h = heads[i];
      if (h.closest('[data-ss]') || h.children.length > 1) continue;
      var t = (h.textContent || '').trim();
      if (SUGGESTED_LINES.indexOf(t) < 0 && !CAUGHT_UP.test(t)) continue;
      if (h.closest('article')) continue; // posts are handled one by one
      var box = h;
      for (var k = 0; k < 8; k++) {
        var p = box.parentElement;
        if (!p || p.tagName === 'MAIN' || p === document.body || p.querySelector('article')) break;
        if (p.getBoundingClientRect().height > 900) break;
        box = p;
      }
      if (box !== h) hide(box, 'suggested');
    }
  }

  function scanFeed() {
    var feed = onFeed();
    var marker = (C.suggested && feed) ? afterCaughtUp() : null;
    var posts = document.querySelectorAll('article:not([data-ss])');
    for (var i = 0; i < posts.length; i++) {
      var a = posts[i];
      var ls = lines(a);
      var kind = null;
      if (C.ads && hasLine(ls, AD_LINES, AD_STARTS)) kind = 'ad';
      else if (C.suggested && hasLine(ls, SUGGESTED_LINES, SUGGESTED_STARTS)) kind = 'suggested';
      else if (C.suggested && feed && marker && (marker.compareDocumentPosition(a) & 4)) kind = 'suggested';
      else if (C.suggested && feed && followInHeader(a)) kind = 'suggested';
      else if (C.feedReels && a.querySelector('a[href*="/reel/"], a[href*="/reels/"]')) kind = 'reel';
      if (kind) {
        hide(a, kind);
      } else if (ls.length >= 2) {
        // posts fill in bit by bit: look again a few times before trusting them
        var n = +(a.getAttribute('data-ss-n') || 0) + 1;
        if (n >= 6) a.setAttribute('data-ss', 'ok'); else a.setAttribute('data-ss-n', n);
      }
    }
    if (C.suggested) hideSuggestionRows();
  }

  api.run = function () {
    try {
      addStyle();
      if (navSel.length) document.querySelectorAll(navSel.join(',')).forEach(collapse);
      if (C.home) document.documentElement.classList.toggle('ss-home', location.pathname === '/');
      if (C.ads || C.suggested || C.feedReels) scanFeed();
    } catch (e) {}
  };

  function start() {
    if (!document.documentElement) { setTimeout(start, 30); return; }
    var queued = false;
    new MutationObserver(function () {
      if (queued) return;
      queued = true;
      setTimeout(function () { queued = false; api.run(); }, 250);
    }).observe(document.documentElement, { childList: true, subtree: true });
    api.run();
  }
  start();
})();
