// Runs inside Facebook / X / Reddit / LinkedIn in ScrollStop's browser.
// ScrollStop puts `var SS_CFG = {hide:[css], item:css, ad:regex, suggested:regex};` in front.
(function () {
  if (window.__scrollstop) { window.__scrollstop.run(); return; }
  var C = (typeof SS_CFG !== 'undefined') ? SS_CFG : {};
  var api = window.__scrollstop = {};
  var hide = C.hide || [];
  var css = hide.length ? hide.join(',') + '{display:none!important}' : '';
  var AD = C.ad ? new RegExp(C.ad, 'im') : null;
  var SUGGESTED = C.suggested ? new RegExp(C.suggested, 'im') : null;

  function tell(kind) { try { ScrollStop.hidden(kind); } catch (e) {} }

  function addStyle() {
    if (!css || document.getElementById('ss-style')) return;
    var s = document.createElement('style');
    s.id = 'ss-style';
    s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
  }

  // Remove the small wrapper a hidden tab button sat in, so the other buttons close the gap.
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

  function scanFeed() {
    if (!C.item || (!AD && !SUGGESTED)) return;
    var items = document.querySelectorAll(C.item);
    for (var i = 0; i < items.length; i++) {
      var a = items[i];
      if (a.getAttribute('data-ss') === 'done') continue;
      var text = a.innerText || '';
      var kind = (AD && AD.test(text)) ? 'ad' : (SUGGESTED && SUGGESTED.test(text)) ? 'suggested' : null;
      if (kind) {
        a.setAttribute('data-ss', 'done');
        a.style.setProperty('display', 'none', 'important');
        tell(kind);
      } else {
        var n = +(a.getAttribute('data-ss-n') || 0) + 1;
        if (n >= 4) a.setAttribute('data-ss', 'done'); else a.setAttribute('data-ss-n', n);
      }
    }
  }

  api.run = function () {
    try {
      addStyle();
      if (hide.length) document.querySelectorAll(hide.join(',')).forEach(function (el) {
        if (el.tagName === 'A') collapse(el);
      });
      scanFeed();
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
