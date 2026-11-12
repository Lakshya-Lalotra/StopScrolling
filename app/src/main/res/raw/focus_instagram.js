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

  var AD = /(^|\n)\s*(Sponsored|Paid partnership[^\n]*)\s*(\n|$)/;
  var SUGGESTED = /(^|\n)\s*(Suggested for you|Suggested posts|Suggested reels|Because you (liked|follow)[^\n]*)\s*(\n|$)/i;

  function scanFeed() {
    var posts = document.querySelectorAll('article:not([data-ss])');
    for (var i = 0; i < posts.length; i++) {
      var a = posts[i];
      var text = a.innerText || '';
      var kind = null;
      if (C.ads && AD.test(text)) kind = 'ad';
      else if (C.suggested && SUGGESTED.test(text)) kind = 'suggested';
      else if (C.feedReels && a.querySelector('a[href*="/reel/"], a[href*="/reels/"]')) kind = 'reel';
      if (kind) {
        a.setAttribute('data-ss', kind);
        a.style.setProperty('display', 'none', 'important');
        tell(kind);
      } else {
        // posts fill in bit by bit: look again a few times before trusting them
        var n = +(a.getAttribute('data-ss-n') || 0) + 1;
        if (n >= 4) a.setAttribute('data-ss', 'ok'); else a.setAttribute('data-ss-n', n);
      }
    }
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
