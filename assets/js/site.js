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

  /* Demo video: no autoplay for reduced motion, pause while off screen, a visible pause button. */
  var video = d.querySelector('.screen video');
  var btn = d.querySelector('.play-toggle');
  if (video) {
    var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var userPaused = reduce;
    var label = function () {
      if (!btn) return;
      var playing = !video.paused;
      btn.setAttribute('aria-label', playing ? 'Pause the demo video' : 'Play the demo video');
      btn.innerHTML = playing
        ? '<svg viewBox="0 0 16 16" aria-hidden="true"><rect x="3" y="2" width="3.5" height="12" rx="1" fill="currentColor"/><rect x="9.5" y="2" width="3.5" height="12" rx="1" fill="currentColor"/></svg>'
        : '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 2.5v11l9.5-5.5z" fill="currentColor"/></svg>';
    };
    if (reduce) { video.removeAttribute('autoplay'); video.pause(); }
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
          if (en.isIntersecting) { if (!userPaused) { var p = video.play(); if (p && p.catch) p.catch(function () {}); } }
          else if (!video.paused) { video.pause(); }
        });
      }, { threshold: 0.2 }).observe(video);
    }
    label();
  }

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
