/* Atom site: menu, header rule, demo video, copy buttons. No tracking, no storage. */
(function () {
  var d = document;
  var head = d.querySelector('.site-head');
  var toggle = d.querySelector('.nav-toggle');
  var nav = d.getElementById('site-nav');

  if (toggle && nav) {
    toggle.addEventListener('click', function () {
      var open = nav.classList.toggle('is-open');
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    d.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && nav.classList.contains('is-open')) {
        nav.classList.remove('is-open');
        toggle.setAttribute('aria-expanded', 'false');
        toggle.focus();
      }
    });
  }

  if (head) {
    var onScroll = function () { head.classList.toggle('is-scrolled', window.scrollY > 8); };
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
  }

  var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* Hero: type the real request, tick in the real steps, then play the recording. */
  var run = d.querySelector('.run');
  if (run && !reduceMotion && 'IntersectionObserver' in window) {
    window.__atomRunDone = false;
    var typed = run.querySelector('.typed');
    var full = typed.getAttribute('data-text');
    var steps = run.querySelectorAll('.run-steps li');
    typed.textContent = '';
    run.classList.add('pending', 'typing');
    var go = function () {
      var t0 = null;
      var tick = function (now) {
        if (t0 === null) t0 = now;
        var i = Math.min(full.length, Math.floor((now - t0) / 34));
        typed.textContent = full.slice(0, i);
        if (i < full.length) { requestAnimationFrame(tick); return; }
        run.classList.remove('typing');
        var k = 0;
        var step = function () {
          if (k < steps.length) { steps[k++].classList.add('on'); setTimeout(step, k === steps.length ? 250 : 420); return; }
          window.__atomRunDone = true;
          if (window.__atomVideo) window.__atomVideo.start();
        };
        setTimeout(step, 300);
      };
      setTimeout(function () { requestAnimationFrame(tick); }, 350);
    };
    var ro = new IntersectionObserver(function (es) { if (es[0].isIntersecting) { ro.disconnect(); go(); } }, { threshold: 0.6 });
    ro.observe(run);
  }

  /* One coin: on Home the hero coin flies into the header logo slot as you scroll. */
  var host = d.querySelector('.coin3d');
  var slot = d.querySelector('body.home .brand-coin');
  var root = d.documentElement;
  if (host && slot) {
    var fly = host.querySelector('.coin-fly');
    var useMorph = !reduceMotion;
    var sda = window.CSS && CSS.supports && CSS.supports('animation-timeline: scroll()');
    var D = 400, geo = null, docked = null;
    var measure = function () {
      var prevT = fly.style.transform;
      var r = host.getBoundingClientRect();
      var sr = slot.getBoundingClientRect();
      var sy = window.scrollY, sx = window.scrollX;
      geo = { x0: r.left + sx, y0: r.top + sy, w: r.width, x1: sr.left, y1: sr.top, k: sr.width / r.width };
      D = Math.max(160, Math.round(geo.y0 + geo.w * 0.55 - geo.y1));
      root.style.setProperty('--cw', geo.w + 'px');
      root.style.setProperty('--x0', geo.x0 + 'px');
      root.style.setProperty('--y0', geo.y0 + 'px');
      root.style.setProperty('--x1', geo.x1 + 'px');
      root.style.setProperty('--y1', geo.y1 + 'px');
      root.style.setProperty('--k', geo.k.toFixed(4));
      root.style.setProperty('--D', D + 'px');
      fly.style.transform = prevT;
    };
    var setDock = function (on) {
      if (on === docked) return;
      docked = on;
      root.classList.toggle(useMorph ? 'coin-docked' : 'no-morph-docked', on);
      host.dispatchEvent(new Event(on ? 'coin:dock' : 'coin:undock'));
    };
    if (useMorph) {
      root.classList.add('coin-morph');
      if (sda) root.classList.add('sda');
      measure();
      var queued = false;
      var update = function () {
        queued = false;
        var y = window.scrollY;
        var p = Math.max(0, Math.min(1, y / D));
        if (!sda) {
          var x = geo.x0 + (geo.x1 - geo.x0) * p - window.scrollX * (1 - p);
          var yy = (geo.y0 - y) + (geo.y1 - (geo.y0 - y)) * p;
          var k = 1 + (geo.k - 1) * p;
          fly.style.transform = 'translate3d(' + x.toFixed(1) + 'px,' + yy.toFixed(1) + 'px,0) scale(' + k.toFixed(4) + ')';
          root.style.setProperty('--dock', p.toFixed(3));
        }
        setDock(p >= 0.999);
        root.classList.toggle('scrolled', y > 40);
      };
      window.addEventListener('scroll', function () { if (!queued) { queued = true; requestAnimationFrame(update); } }, { passive: true });
      window.addEventListener('resize', function () {
        root.classList.remove('coin-morph', 'sda');
        fly.style.transform = '';
        requestAnimationFrame(function () { root.classList.add('coin-morph'); if (sda) root.classList.add('sda'); measure(); update(); });
      });
      update();
    } else if ('IntersectionObserver' in window) {
      /* Reduced motion: no flight. The header coin fades in once the hero coin is out of view. */
      new IntersectionObserver(function (es) { setDock(!es[0].isIntersecting); }, { threshold: 0 }).observe(host);
    }
    host.addEventListener('pointerdown', function () { host.classList.add('touched'); });
  }

  /* Short footage loops: play only while visible; never autoplay with reduced motion. */
  d.querySelectorAll('.loop video').forEach(function (v) {
    v.removeAttribute('autoplay');
    if (reduceMotion) { v.setAttribute('controls', ''); return; }
    if (!('IntersectionObserver' in window)) { v.play(); return; }
    new IntersectionObserver(function (es) {
      es.forEach(function (en) { if (en.isIntersecting) { var p = v.play(); if (p && p.catch) p.catch(function () {}); } else v.pause(); });
    }, { threshold: 0.25 }).observe(v);
  });

  /* Scroll reveals and counters. */
  var reveals = d.querySelectorAll('.reveal');
  var countUp = function (el) {
    var to = parseInt(el.getAttribute('data-to'), 10), t0 = null;
    var f = function (now) {
      if (t0 === null) t0 = now;
      var p = Math.min(1, (now - t0) / 900);
      el.textContent = Math.round(to * (1 - Math.pow(1 - p, 3)));
      if (p < 1) requestAnimationFrame(f);
    };
    requestAnimationFrame(f);
  };
  if (reveals.length && 'IntersectionObserver' in window && !reduceMotion) {
    var rio = new IntersectionObserver(function (es) {
      es.forEach(function (en) {
        if (!en.isIntersecting) return;
        en.target.classList.add('in');
        en.target.querySelectorAll('.count').forEach(countUp);
        rio.unobserve(en.target);
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
    reveals.forEach(function (el) { rio.observe(el); });
  } else {
    reveals.forEach(function (el) { el.classList.add('in'); });
  }

  /* A slight parallax on phone screenshots (desktop, motion allowed). */
  if (!reduceMotion && window.matchMedia('(min-width: 980px)').matches) {
    var phonesEls = [].slice.call(d.querySelectorAll('.phone, .bench-media .phone'));
    phonesEls.forEach(function (p) { p.classList.add('parallax'); });
    var queued = false;
    var px = function () {
      queued = false;
      var vh = window.innerHeight;
      phonesEls.forEach(function (p) {
        var r = p.getBoundingClientRect();
        if (r.bottom < 0 || r.top > vh) return;
        var off = ((r.top + r.height / 2) - vh / 2) / vh;
        p.style.transform = 'translate3d(0,' + (off * -18).toFixed(1) + 'px,0)';
      });
    };
    window.addEventListener('scroll', function () { if (!queued) { queued = true; requestAnimationFrame(px); } }, { passive: true });
    px();
  }

  /* The metal sheen follows the pointer over primary buttons. */
  d.querySelectorAll('.btn.sheen').forEach(function (b) {
    b.addEventListener('pointermove', function (e) {
      var r = b.getBoundingClientRect();
      b.style.setProperty('--mx', (e.clientX - r.left) + 'px');
      b.style.setProperty('--my', (e.clientY - r.top) + 'px');
    });
  });

  /* Demo video: no autoplay for reduced motion, pause while off screen, a visible pause button. */
  var video = d.querySelector('.screen video');
  var btn = d.querySelector('.play-toggle');
  if (video) {
    var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var userPaused = reduce;
    window.__atomVideo = { start: function () { if (!userPaused && video.getBoundingClientRect().top < window.innerHeight) { var p = video.play(); if (p && p.catch) p.catch(function () {}); } } };
    var label = function () {
      if (!btn) return;
      var playing = !video.paused;
      btn.setAttribute('aria-label', playing ? 'Pause the demo video' : 'Play the demo video');
      btn.innerHTML = playing
        ? '<svg viewBox="0 0 16 16" aria-hidden="true"><rect x="3" y="2" width="3.5" height="12" rx="1" fill="currentColor"/><rect x="9.5" y="2" width="3.5" height="12" rx="1" fill="currentColor"/></svg>'
        : '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 2.5v11l9.5-5.5z" fill="currentColor"/></svg>';
    };
    video.removeAttribute('autoplay'); video.pause();
    video.addEventListener('play', label);
    video.addEventListener('pause', label);
    if (btn) {
      btn.hidden = false;
      btn.addEventListener('click', function () {
        if (video.paused) { userPaused = false; video.play(); } else { userPaused = true; video.pause(); }
      });
    }
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (entries) {
        entries.forEach(function (en) {
          if (en.isIntersecting) { if (!userPaused && window.__atomRunDone !== false) { var p = video.play(); if (p && p.catch) p.catch(function () {}); } }
          else if (!video.paused) { video.pause(); }
        });
      }, { threshold: 0.2 }).observe(video);
    }
    label();
  }

  /* Swipe rows on phones: dots that show where you are and jump to a card. */
  d.querySelectorAll('.phones, .bleed').forEach(function (row, r) {
    var items = row.children;
    if (items.length < 2) return;
    var dots = d.createElement('div');
    dots.className = 'dots';
    dots.setAttribute('role', 'group');
    dots.setAttribute('aria-label', 'Screens');
    var btns = [];
    for (var i = 0; i < items.length; i++) {
      (function (i) {
        var b = d.createElement('button');
        b.type = 'button';
        b.setAttribute('aria-label', 'Show screen ' + (i + 1) + ' of ' + items.length);
        b.addEventListener('click', function () {
          row.scrollTo({ left: items[i].offsetLeft - row.offsetLeft - parseFloat(getComputedStyle(row).paddingLeft || 0), behavior: 'smooth' });
        });
        dots.appendChild(b);
        btns.push(b);
      })(i);
    }
    row.parentNode.insertBefore(dots, row.nextSibling);
    var mark = function () {
      var best = 0, bestD = Infinity, left = row.getBoundingClientRect().left;
      for (var i = 0; i < items.length; i++) {
        var dist = Math.abs(items[i].getBoundingClientRect().left - left);
        if (dist < bestD) { bestD = dist; best = i; }
      }
      if (row.scrollLeft + row.clientWidth >= row.scrollWidth - 4) best = items.length - 1;
      btns.forEach(function (b, i) { b.setAttribute('aria-current', i === best ? 'true' : 'false'); });
    };
    row.addEventListener('scroll', function () { window.requestAnimationFrame(mark); }, { passive: true });
    mark();
  });

  /* Copy buttons on code blocks and fingerprints. */
  d.querySelectorAll('pre, .fingerprint').forEach(function (el) {
    if (!navigator.clipboard) return;
    var b = d.createElement('button');
    b.type = 'button';
    b.className = 'copy';
    b.textContent = 'Copy';
    b.addEventListener('click', function () {
      var text = (el.querySelector('code') || el).innerText.replace(/\s*Copy$/, '');
      navigator.clipboard.writeText(text.trim()).then(function () {
        b.textContent = 'Copied';
        setTimeout(function () { b.textContent = 'Copy'; }, 1600);
      });
    });
    if (el.classList.contains('fingerprint')) { el.style.position = 'relative'; el.style.paddingRight = '76px'; }
    el.appendChild(b);
  });
})();
