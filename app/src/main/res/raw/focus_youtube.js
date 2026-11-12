// Runs inside m.youtube.com in ScrollStop's browser. ScrollStop puts `var SS_CFG = {...};` in front.
(function () {
  if (window.__scrollstop) { window.__scrollstop.run(); return; }
  var C = (typeof SS_CFG !== 'undefined') ? SS_CFG : {};
  var api = window.__scrollstop = {};

  var hide = [];
  if (C.shorts) hide.push(
    'ytm-pivot-bar-item-renderer:has(.pivot-shorts)',
    'ytm-pivot-bar-item-renderer:has([class*="shorts"])',
    'ytm-reel-shelf-renderer',
    'ytm-shorts-lockup-view-model',
    'ytm-shorts-lockup-view-model-v2',
    'grid-shelf-view-model:has(ytm-shorts-lockup-view-model)',
    'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model)',
    'ytm-rich-section-renderer:has(ytm-reel-shelf-renderer)',
    'ytm-item-section-renderer:has(ytm-reel-shelf-renderer)',
    'ytm-rich-item-renderer:has(a[href^="/shorts"])',
    'ytm-video-with-context-renderer:has(a[href^="/shorts"])',
    'ytm-compact-video-renderer:has(a[href^="/shorts"])',
    'a[href^="/shorts"]'
  );
  if (C.ads) hide.push(
    'ytm-promoted-sparkles-web-renderer', 'ytm-promoted-video-renderer', 'ad-slot-renderer',
    'ytm-companion-ad-renderer', 'ytm-rich-item-renderer:has(ad-slot-renderer)'
  );
  var css = hide.length ? hide.join(',') + '{display:none!important}' : '';
  if (C.home) css += 'html.ss-home ytm-browse{display:none!important}' +
    'html.ss-home body::after{content:"Home feed is hidden. Search for what you came for, or open Subscriptions.";' +
    'position:fixed;left:24px;right:24px;top:40%;text-align:center;font:16px sans-serif;color:#888;}';

  function addStyle() {
    if (!css || document.getElementById('ss-style')) return;
    var s = document.createElement('style');
    s.id = 'ss-style';
    s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
  }

  var counted = 0;
  api.run = function () {
    try {
      addStyle();
      if (C.home) {
        var p = location.pathname;
        document.documentElement.classList.toggle('ss-home', p === '/' || p === '/feed/explore');
      }
      if (C.ads) {
        var n = document.querySelectorAll('ad-slot-renderer, ytm-promoted-sparkles-web-renderer').length;
        if (n > counted) { for (var i = counted; i < n; i++) { try { ScrollStop.hidden('ad'); } catch (e) {} } counted = n; }
      }
    } catch (e) {}
  };

  function start() {
    if (!document.documentElement) { setTimeout(start, 30); return; }
    var queued = false;
    new MutationObserver(function () {
      if (queued) return;
      queued = true;
      setTimeout(function () { queued = false; api.run(); }, 300);
    }).observe(document.documentElement, { childList: true, subtree: true });
    api.run();
  }
  start();
})();
