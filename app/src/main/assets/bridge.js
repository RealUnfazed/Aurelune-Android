/* Injected by the Android app after the Aurelune page loads. It changes nothing on the page. It tells the app
     1. when audio starts and stops (so the app can keep playing in the background), and
     2. what is playing (title, artist, cover, position), read from the site's own player, so the notification and lock
        screen can show it, and it lets the app press play / pause / next / previous / seek on that player.
   The site's player lives in the ES module /js/player.js (export const player). If that ever changes, only part 2 stops
   working; the music itself and part 1 do not depend on it. */
(function () {
  var A = window.AurAndroid;
  if (window.__aurBridge || !A) return;
  window.__aurBridge = true;

  /* ---------------------------------------------------------------- 1. audio started / stopped (works with any site) */

  var P = null;             // the site's player object, once found (part 2)
  var active = new Set();   // media elements that are playing right now
  var lastEl = null;        // the one that played last
  var last = null;          // last state sent to the app
  var timer = null;

  function send(state) {
    last = state;
    try { A.setPlaying(state); } catch (e) { /* the app is gone */ }
  }

  function report() {
    if (P) return;   // the player object reports play / pause itself (part 2); this is only the fallback
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
    el.addEventListener('playing', function () { lastEl = el; active.add(el); report(); });
    ['pause', 'ended', 'emptied', 'error', 'abort'].forEach(function (type) {
      el.addEventListener(type, function () { active['delete'](el); report(); });
    });
    if (!el.paused && !el.ended) { lastEl = el; active.add(el); report(); }
  }

  // The player creates its audio elements in script (new Audio()), so they never appear in the page: hook them on play().
  var originalPlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    hook(this);
    return originalPlay.apply(this, arguments);
  };
  Array.prototype.forEach.call(document.querySelectorAll('audio,video'), hook);

  /* ---------------------------------------------------------------- 2. what is playing + remote control */

  function getPlayer() {
    if (P) return Promise.resolve(P);
    return import(location.origin + '/js/player.js').then(function (m) { P = m.player || null; return P; }, function () { return null; });
  }

  function abs(url) {
    try { return url ? new URL(url, location.href).href : ''; } catch (e) { return ''; }
  }

  /* Draws the cover into a small JPEG so the app gets it even when it is an SVG or needs your session. '' = could not. */
  function rasterize(url, done) {
    var img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = function () {
      try {
        var size = 320, c = document.createElement('canvas'), g;
        c.width = c.height = size;
        g = c.getContext('2d');
        g.fillStyle = '#0a0b14';
        g.fillRect(0, 0, size, size);
        var w = img.naturalWidth || size, h = img.naturalHeight || size, s = Math.max(size / w, size / h);
        g.drawImage(img, (size - w * s) / 2, (size - h * s) / 2, w * s, h * s);
        done(c.toDataURL('image/jpeg', 0.85).split(',')[1] || '');
      } catch (e) { done(''); }   // a cover from another site without CORS: the app tries to download it itself
    };
    img.onerror = function () { done(''); };
    img.src = url;
  }

  var sentPlaying = null;   // last play state sent to the app

  // Commands from the notification / lock screen / headset buttons.
  window.__aurControl = function (action, arg) {
    getPlayer().then(function (p) {
      if (p) {
        if (action === 'pause') sentPlaying = false;   // the app already knows: no "hold" for a pause it asked for
        if (action === 'play') p.play();
        else if (action === 'pause') p.pause();
        else if (action === 'next') p.next();
        else if (action === 'prev') p.prev();
        else if (action === 'seek') p.seekTo(Number(arg) || 0);
        return;
      }
      // Fallback without the player object: play / pause the audio element itself.
      if (action === 'pause') active.forEach(function (el) { el.pause(); });
      else if (action === 'play' && lastEl) lastEl.play();
    });
  };

  function init(tries) {
    getPlayer().then(function (p) {
      if (!p) { if (tries < 5) setTimeout(function () { init(tries + 1); }, 3000); return; }
      wire(p);
    });
  }

  function wire(p) {
    var lastArt = null, cleared = false;
    var sentPos = 0, sentAt = 0, hold = null;

    function push(settled) {
      var item = p.current;
      if (!item) {
        if (!cleared) { cleared = true; lastArt = null; sentPlaying = null; try { A.clearMedia(); } catch (e) { /* gone */ } }
        return;
      }
      cleared = false;
      var md = navigator.mediaSession && navigator.mediaSession.metadata;   // the site fills this in on every change
      var title = (md && md.title) || item.title || '';
      var artist = (md && md.artist) || '';
      var album = (md && md.album) || '';
      var art = abs((md && md.artwork && md.artwork.length && md.artwork[0].src) || item.cover || '');
      var dur = Number(p.durationSec);
      dur = isFinite(dur) && dur > 0 ? Math.round(dur * 1000) : 0;
      var pos = Math.max(0, Math.round((Number(p.position) || 0) * 1000));
      var playing = !!p.isPlaying;

      // Between two songs the player is "not playing" for a moment: only believe a pause once it has lasted a little.
      if (!playing && sentPlaying === true && !settled) {
        if (!hold) hold = setTimeout(function () { hold = null; push(true); }, 700);
        playing = true;
      } else if (playing && hold) {
        clearTimeout(hold); hold = null;
      }

      try { A.setMedia(String(title), String(artist), String(album), art, dur, pos, playing); } catch (e) { return; }
      sentPlaying = playing; sentPos = pos; sentAt = Date.now();

      if (art !== lastArt) {
        lastArt = art;
        if (art) rasterize(art, function (b64) { try { A.setArtwork(art, b64); } catch (e) { /* gone */ } });
      }
    }

    p.addEventListener('change', function () { push(false); });
    // 'time' fires all the time while playing: only a jump (a seek) is worth telling the app, it can count the time itself.
    p.addEventListener('time', function () {
      if (!p.current || sentPlaying === null) return;
      var expected = sentPos + (sentPlaying ? Date.now() - sentAt : 0);
      if (Math.abs((Number(p.position) || 0) * 1000 - expected) > 1500) push(false);
    });
    push(false);
  }

  init(0);
})();
