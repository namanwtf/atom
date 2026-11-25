package atom.web.browser

/** Page scripts the agent browser injects. Each is an expression returning a JSON string. */
internal object BrowserScripts {
    private const val PRELUDE = """
const __vis = (el) => { const r = el.getBoundingClientRect(); const s = getComputedStyle(el);
  return r.width > 1 && r.height > 1 && s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0'; };
const __label = (el) => ((el.getAttribute('aria-label') || el.innerText || el.value || el.placeholder || el.title ||
  el.getAttribute('name') || el.getAttribute('alt') || '') + '').trim().replace(/\s+/g, ' ').slice(0, 120);
const __SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,label,[role=button],[role=link],[role=checkbox],' +
  '[role=radio],[role=tab],[role=menuitem],[role=option],[role=switch],[role=combobox],[role=textbox],[onclick],[contenteditable=""],[contenteditable=true],[tabindex]:not([tabindex="-1"])';
const __find = (a) => {
  if (a.id != null) { const e = document.querySelector('[data-atom-id="' + a.id + '"]'); if (e) return e; }
  if (a.selector) { try { const e = document.querySelector(a.selector); if (e) return e; } catch (x) {} }
  if (a.text) { const q = a.text.toLowerCase(); let best = null;
    for (const e of document.querySelectorAll(__SEL)) { if (!__vis(e)) continue; const l = __label(e).toLowerCase();
      if (l === q) return e; if (!best && l.includes(q)) best = e; }
    return best; }
  return null; };
"""

    private fun wrap(args: String, body: String) = "(function(a){$PRELUDE\n$body\n})($args)"

    fun find(args: String) = wrap(
        args,
        """
const q = a.query ? a.query.toLowerCase() : null; const limit = a.limit || 60;
let nodes; try { nodes = a.selector ? document.querySelectorAll(a.selector) : document.querySelectorAll(__SEL); }
catch (x) { return JSON.stringify({error: 'Invalid selector: ' + x.message}); }
let next = window.__atomNext || 1; const out = []; let matched = 0;
for (const el of nodes) {
  if (!__vis(el)) continue;
  const label = __label(el); const href = el.href || '';
  if (q && !(label.toLowerCase().includes(q) || href.toLowerCase().includes(q) ||
    ((el.getAttribute('name') || '') + ' ' + (el.placeholder || '') + ' ' + (el.id || '')).toLowerCase().includes(q))) continue;
  matched++;
  if (out.length >= limit) continue;
  let id = el.getAttribute('data-atom-id'); if (!id) { id = String(next++); el.setAttribute('data-atom-id', id); }
  const r = el.getBoundingClientRect(); const tag = el.tagName.toLowerCase();
  out.push({id: +id, tag: tag, type: el.type || el.getAttribute('role') || '', label: label, href: href.slice(0, 200),
    value: (tag === 'input' && el.type !== 'password') || tag === 'textarea' || tag === 'select' ? String(el.value || '').slice(0, 80) : '',
    checked: el.checked === true, disabled: !!el.disabled, inView: r.top < innerHeight && r.bottom > 0});
}
window.__atomNext = next;
return JSON.stringify({url: location.href, title: document.title, matched: matched, items: out});
""",
    )

    fun click(args: String) = wrap(
        args,
        """
const el = __find(a); if (!el) return JSON.stringify({ok: false, error: 'Element not found. Call browser_find again; ids change after navigation.'});
el.scrollIntoView({block: 'center', inline: 'center'});
const r = el.getBoundingClientRect();
const o = {bubbles: true, cancelable: true, composed: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, button: 0};
try { el.dispatchEvent(new PointerEvent('pointerdown', o)); } catch (x) {}
el.dispatchEvent(new MouseEvent('mousedown', o)); if (el.focus) el.focus();
try { el.dispatchEvent(new PointerEvent('pointerup', o)); } catch (x) {}
el.dispatchEvent(new MouseEvent('mouseup', o)); el.click();
return JSON.stringify({ok: true, label: __label(el), tag: el.tagName.toLowerCase()});
""",
    )

    fun type(args: String) = wrap(
        args,
        """
const el = __find(a) || (a.id == null && !a.selector && !a.text ? document.activeElement : null);
if (!el || el === document.body) return JSON.stringify({ok: false, error: 'Input not found. Use browser_find to get its id.'});
el.scrollIntoView({block: 'center'}); if (el.focus) el.focus();
const tag = el.tagName;
if (el.isContentEditable) {
  if (a.clear) { document.execCommand('selectAll', false, null); document.execCommand('delete', false, null); }
  document.execCommand('insertText', false, a.value);
} else if (tag === 'SELECT') {
  const want = String(a.value).toLowerCase(); let hit = null;
  for (const op of el.options) { if (op.value.toLowerCase() === want || op.text.trim().toLowerCase() === want) { hit = op; break; } }
  if (!hit) for (const op of el.options) { if (op.text.toLowerCase().includes(want)) { hit = op; break; } }
  if (!hit) return JSON.stringify({ok: false, error: 'No option matches. Options: ' + Array.from(el.options).map(o => o.text.trim()).slice(0, 30).join(' | ')});
  el.value = hit.value; el.dispatchEvent(new Event('input', {bubbles: true})); el.dispatchEvent(new Event('change', {bubbles: true}));
} else if (el.type === 'checkbox' || el.type === 'radio') {
  const want = ['true', '1', 'yes', 'on', 'checked'].includes(String(a.value).toLowerCase());
  if (el.checked !== want) el.click();
} else {
  const proto = tag === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
  setter.call(el, a.clear ? a.value : String(el.value || '') + a.value);
  el.dispatchEvent(new InputEvent('input', {bubbles: true, data: a.value, inputType: 'insertText'}));
  el.dispatchEvent(new Event('change', {bubbles: true}));
}
let submitted = false;
if (a.submit) {
  const f = el.form || el.closest('form');
  if (f) { if (f.requestSubmit) f.requestSubmit(); else f.submit(); submitted = true; }
  else { for (const t of ['keydown', 'keypress', 'keyup']) el.dispatchEvent(new KeyboardEvent(t, {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true})); submitted = true; }
}
return JSON.stringify({ok: true, label: __label(el), submitted: submitted, masked: el.type === 'password'});
""",
    )

    fun submit(args: String) = wrap(
        args,
        """
let el = __find(a) || document.activeElement;
const f = el && (el.tagName === 'FORM' ? el : (el.form || el.closest('form')));
if (f) {
  const btn = f.querySelector('button[type=submit],input[type=submit],button:not([type])');
  if (btn && __vis(btn)) btn.click(); else if (f.requestSubmit) f.requestSubmit(); else f.submit();
  return JSON.stringify({ok: true, how: 'form'});
}
if (el && el !== document.body) {
  for (const t of ['keydown', 'keypress', 'keyup']) el.dispatchEvent(new KeyboardEvent(t, {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true}));
  return JSON.stringify({ok: true, how: 'enter'});
}
return JSON.stringify({ok: false, error: 'No form or focused field to submit.'});
""",
    )

    fun scroll(args: String) = wrap(
        args,
        """
if (a.id != null || a.selector || a.text) { const el = __find(a); if (!el) return JSON.stringify({ok: false, error: 'Element not found'}); el.scrollIntoView({block: 'center'}); }
else if (a.direction === 'top') window.scrollTo(0, 0);
else if (a.direction === 'bottom') window.scrollTo(0, document.documentElement.scrollHeight);
else window.scrollBy(0, (a.direction === 'up' ? -1 : 1) * innerHeight * 0.85 * (a.amount || 1));
const h = document.documentElement.scrollHeight;
return JSON.stringify({ok: true, y: Math.round(scrollY), height: h, viewport: innerHeight, atBottom: scrollY + innerHeight >= h - 4});
""",
    )

    fun present(args: String) = wrap(
        args,
        """
if (a.selector) { try { return JSON.stringify({found: !!document.querySelector(a.selector), ready: document.readyState}); } catch (x) { return JSON.stringify({error: x.message}); } }
if (a.text) return JSON.stringify({found: (document.body && document.body.innerText || '').toLowerCase().includes(a.text.toLowerCase()), ready: document.readyState});
return JSON.stringify({found: document.readyState === 'complete', ready: document.readyState});
""",
    )

    const val HTML = "(function(){return (document.documentElement ? document.documentElement.outerHTML : '').slice(0, 4000000);})()"

    const val TEXT = "(function(){return JSON.stringify({title: document.title, url: location.href, text: (document.body ? document.body.innerText : '').slice(0, 2000000)});})()"

    const val LINKS = """(function(){const out=[];const seen=new Set();for(const a of document.querySelectorAll('a[href]')){const h=a.href;
if(!h||seen.has(h)||!/^https?:/.test(h))continue;seen.add(h);out.push({t:((a.innerText||a.getAttribute('aria-label')||a.title||'')+'').trim().replace(/\s+/g,' ').slice(0,100),h:h});if(out.length>=300)break;}
return JSON.stringify(out);})()"""

    const val INFO = "(function(){return JSON.stringify({title: document.title, url: location.href, ready: document.readyState, y: Math.round(scrollY), height: document.documentElement ? document.documentElement.scrollHeight : 0});})()"
}
