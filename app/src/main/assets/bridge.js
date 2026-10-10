/* Injected by the Android app after the Aurelune page loads. It tells the app when audio starts and stops, so the app can
   keep the music going in the background. It changes nothing on the page itself. */
(function () {
  if (window.__aurBridge || !window.AurAndroid) return;
  window.__aurBridge = true;

  var active = new Set();   // media elements that are playing right now
  var last = null;          // last state sent to the app
  var timer = null;

  function send(state) {
    last = state;
    try { window.AurAndroid.setPlaying(state); } catch (e) { /* the app is gone */ }
  }

  function report() {
    if (active.size > 0) {
      if (timer) { clearTimeout(timer); timer = null; }
      if (last !== true) send(true);
    } else if (last !== false && !timer) {
      // Wait a moment before saying "stopped": between two songs the old one ends a moment before the next starts.
      timer = setTimeout(function () {
        timer = null;
        if (active.size === 0) send(false);
      }, 2500);
    }
  }

  var hooked = new WeakSet();
  function hook(el) {
    if (hooked.has(el)) return;
    hooked.add(el);
    el.addEventListener('playing', function () { active.add(el); report(); });
    ['pause', 'ended', 'emptied', 'error', 'abort'].forEach(function (type) {
      el.addEventListener(type, function () { active['delete'](el); report(); });
    });
    if (!el.paused && !el.ended) { active.add(el); report(); }
  }

  // The player creates its audio elements in script (new Audio()), so they never appear in the page: hook them on play().
  var originalPlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    hook(this);
    return originalPlay.apply(this, arguments);
  };
  Array.prototype.forEach.call(document.querySelectorAll('audio,video'), hook);
})();
