package com.nuvio.tv.core.server

object IptvSetupWebPage {

    fun page(nonce: String): String = PAGE.replace("NONCE_VALUE", nonce)

    fun ended(nonce: String): String = ENDED.replace("NONCE_VALUE", nonce)

    private const val HEAD = """<!DOCTYPE html>
<html lang="en-AU">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="referrer" content="same-origin">
<meta name="robots" content="noindex, nofollow">
<meta name="color-scheme" content="dark">
<title>Nuvio Live TV setup</title>
<style nonce="NONCE_VALUE">
:root{--bg:#0b0d10;--card:#15181d;--raised:#1c2027;--line:#2a2f38;--text:#f4f6f8;--muted:#a3abb6;--faint:#737c88;--accent:#7cc4ff;--accent-ink:#06121c;--good:#5fd39a;--bad:#ff7a7a;--warn:#ffc46b;--r:16px}
*{box-sizing:border-box}
html,body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;-webkit-text-size-adjust:100%}
body{min-height:100vh;background:radial-gradient(1200px 600px at 10% -10%,#1a2633 0%,transparent 60%),var(--bg)}
main{max-width:640px;margin:0 auto;padding:24px 18px 48px}
header{display:flex;align-items:center;gap:12px;margin-bottom:20px}
.mark{width:40px;height:40px;border-radius:12px;background:linear-gradient(135deg,#7cc4ff,#3f7cff);display:grid;place-items:center;color:#05101a;font-weight:800;font-size:20px}
h1{font-size:20px;margin:0;font-weight:700;letter-spacing:.01em}
.sub{color:var(--muted);font-size:14px;margin:0}
h2{font-size:17px;margin:0 0 4px;font-weight:650}
.card{background:var(--card);border:1px solid var(--line);border-radius:var(--r);padding:18px;margin-bottom:16px}
.note{font-size:13px;color:var(--muted);border:1px solid #3a3322;background:#1d1a12;border-radius:12px;padding:10px 12px;margin-bottom:16px}
.note strong{color:var(--warn)}
.hint{font-size:13px;color:var(--faint);margin:6px 0 0}
label{display:block;font-size:14px;color:var(--muted);margin:14px 0 6px}
input{width:100%;font:inherit;color:var(--text);background:var(--raised);border:1px solid var(--line);border-radius:12px;padding:12px 14px;outline:none}
input:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(124,196,255,.18)}
input.code{font-size:28px;letter-spacing:.35em;text-align:center;font-variant-numeric:tabular-nums}
.row{display:flex;gap:8px;align-items:stretch}
.row input{flex:1}
button{font:inherit;font-weight:600;border-radius:12px;border:1px solid var(--line);background:var(--raised);color:var(--text);padding:11px 16px;cursor:pointer}
button.primary{background:var(--accent);border-color:var(--accent);color:var(--accent-ink)}
button.wide{width:100%;margin-top:18px}
button:disabled{opacity:.5;cursor:default}
.seg{display:grid;grid-template-columns:repeat(3,1fr);gap:6px;background:var(--raised);border:1px solid var(--line);border-radius:12px;padding:4px;margin-top:12px}
.seg button{border:0;background:transparent;color:var(--muted);padding:9px 6px;font-size:14px}
.seg button[aria-pressed="true"]{background:var(--card);color:var(--text);box-shadow:0 1px 0 rgba(255,255,255,.05)}
.list{list-style:none;margin:12px 0 0;padding:0}
.list li{display:flex;align-items:center;gap:12px;padding:12px 0;border-top:1px solid var(--line)}
.list li:first-child{border-top:0}
.grow{flex:1;min-width:0}
.name{font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.meta{font-size:13px;color:var(--faint);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.empty{color:var(--faint);font-size:14px;margin:12px 0 0}
.actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:14px}
.center{justify-content:center}
.error{color:var(--bad);font-size:14px;margin:10px 0 0}
.status{text-align:center;padding:28px 18px}
.status .big{font-size:20px;font-weight:700;margin:10px 0 4px}
.dot{width:14px;height:14px;border-radius:50%;margin:0 auto;background:var(--accent);animation:pulse 1.2s ease-in-out infinite}
.ok .dot{background:var(--good);animation:none}
.no .dot{background:var(--bad);animation:none}
@keyframes pulse{0%,100%{opacity:.35;transform:scale(.85)}50%{opacity:1;transform:scale(1)}}
[hidden]{display:none!important}
footer{color:var(--faint);font-size:12px;text-align:center;margin-top:24px}
</style>
</head>
"""

    private const val PAGE = HEAD + """<body>
<main>
<header><div class="mark" aria-hidden="true">N</div><div><h1>Live TV setup</h1><p class="sub">Add or change sources and guides on your TV</p></div></header>
<p class="note"><strong>Home network only.</strong> This page talks to your TV over your local network without encryption. Don't use it on public or shared Wi-Fi.</p>

<section id="pair" class="card" hidden>
<h2>Enter the code on your TV</h2>
<p class="hint">Your TV shows a 6-digit code next to the QR code.</p>
<form id="pairForm" method="post" novalidate>
<label for="code">Pairing code</label>
<input id="code" class="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]{6}" required>
<p id="pairError" class="error" role="alert" hidden></p>
<button class="primary wide" type="submit" id="pairButton">Connect</button>
</form>
</section>

<section id="home" hidden>
<div class="card">
<h2>Sources</h2>
<p class="hint">Playlists and accounts that provide your channels.</p>
<ul id="sources" class="list"></ul>
<p id="sourcesEmpty" class="empty" hidden>No sources yet.</p>
<div class="actions"><button class="primary" type="button" id="addSource">Add a source</button></div>
</div>
<div class="card">
<h2>Programme guides</h2>
<p class="hint">XMLTV guides that fill in what's on.</p>
<ul id="guides" class="list"></ul>
<p id="guidesEmpty" class="empty" hidden>No guides yet.</p>
<div class="actions"><button type="button" id="addGuide">Add a guide</button></div>
</div>
<p class="hint">To remove a source or guide, use the TV.</p>
</section>

<section id="editor" class="card" hidden>
<h2 id="formTitle">Add a source</h2>
<p id="formHint" class="hint"></p>
<div id="kinds" class="seg" role="group" aria-label="Source type">
<button type="button" data-kind="m3u">M3U playlist</button>
<button type="button" data-kind="xtream">Xtream</button>
<button type="button" data-kind="stalker">Stalker</button>
</div>
<form id="entryForm" method="post" novalidate autocomplete="off">
<label for="label">Name</label>
<input id="label" maxlength="240" autocomplete="off" required>
<label for="address" id="addressLabel">Playlist address</label>
<input id="address" type="url" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" maxlength="16384">
<p id="addressHint" class="hint"></p>
<div id="xtreamFields" hidden>
<label for="username">Username</label>
<input id="username" autocapitalize="off" autocorrect="off" spellcheck="false" maxlength="4096" autocomplete="off">
<label for="password">Password</label>
<div class="row"><input id="password" type="password" maxlength="4096" autocomplete="new-password"><button type="button" id="reveal" aria-pressed="false">Show</button></div>
<p id="credentialHint" class="hint" hidden>Leave blank to keep what's saved on the TV.</p>
</div>
<div id="stalkerFields" hidden>
<label for="mac">MAC address</label>
<input id="mac" autocapitalize="characters" autocorrect="off" spellcheck="false" maxlength="17" placeholder="00:1A:79:00:00:00">
<p id="macHint" class="hint">Six pairs separated by colons, as registered with your provider.</p>
</div>
<p id="formError" class="error" role="alert" hidden></p>
<button class="primary wide" type="submit" id="send">Send to TV</button>
<button class="wide" type="button" id="cancel">Cancel</button>
</form>
</section>

<section id="progress" class="card status" hidden>
<div class="dot"></div>
<p class="big" id="progressTitle">Check your TV</p>
<p class="hint" id="progressText">Confirm the change on your TV to save it.</p>
<div class="actions center"><button class="primary" type="button" id="done" hidden>Done</button></div>
</section>

<section id="ended" class="card status no" hidden>
<div class="dot"></div>
<p class="big" id="endedTitle">This setup link has ended</p>
<p class="hint" id="endedText">Open Live TV setup on your TV and scan the new QR code.</p>
</section>

<footer>Nuvio · works only while the setup screen is open on your TV</footer>
</main>
<script nonce="NONCE_VALUE">
(function () {
  'use strict';
  var base = location.pathname.replace(/[^\/]*$/, '');
  var views = ['pair', 'home', 'editor', 'progress', 'ended'];
  var kindNames = { m3u: 'M3U playlist', xtream: 'Xtream account', stalker: 'Stalker portal', guide: 'XMLTV guide' };
  var current = null;
  var polling = null;

  function el(id) { return document.getElementById(id); }
  function show(name) {
    views.forEach(function (v) { el(v).hidden = v !== name; });
    window.scrollTo(0, 0);
  }
  function setText(id, text) { var node = el(id); node.textContent = text || ''; node.hidden = !text; }

  function api(method, path, body) {
    var options = { method: method, headers: { 'X-Nuvio-Setup': '1' }, credentials: 'same-origin', cache: 'no-store', referrerPolicy: 'same-origin', redirect: 'error' };
    if (body !== undefined) { options.headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(body); }
    return fetch(base + path, options).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (data) { return { status: response.status, data: data || {} }; });
    });
  }

  function ended(title, text) {
    stopPolling();
    el('endedTitle').textContent = title || 'This setup link has ended';
    el('endedText').textContent = text || 'Open Live TV setup on your TV and scan the new QR code.';
    show('ended');
  }

  function lost() {
    ended('Lost touch with the TV', 'The setup screen on the TV may have closed. Check the TV to see what was saved, then scan the QR code again.');
  }

  function common(result) {
    if (result.status === 401) { askForCode('Your session has ended. Enter the code shown on your TV.'); return true; }
    if (result.status === 404 && result.data.error === 'link') { ended(); return true; }
    if (result.status === 429) { alertBox('Too many requests. Wait a minute, then try again.'); return true; }
    return false;
  }

  function alertBox(text) {
    if (!el('editor').hidden) setText('formError', text);
    else if (!el('pair').hidden) setText('pairError', text);
  }

  function askForCode(message) {
    stopPolling();
    el('code').value = '';
    setText('pairError', message);
    show('pair');
    el('code').focus();
  }

  function load() {
    return api('GET', 'api/state').then(function (result) {
      if (common(result)) return;
      if (result.status !== 200) { lost(); return; }
      render(result.data);
      show('home');
    }, lost);
  }

  function item(entry, isGuide) {
    var li = document.createElement('li');
    var text = document.createElement('div');
    text.className = 'grow';
    var name = document.createElement('div');
    name.className = 'name';
    name.textContent = entry.label;
    var meta = document.createElement('div');
    meta.className = 'meta';
    var kind = entry.editable || !isGuide ? kindNames[entry.kind] : 'Provider guide, added automatically';
    meta.textContent = entry.host ? kind + ' · ' + entry.host : kind;
    text.appendChild(name);
    text.appendChild(meta);
    li.appendChild(text);
    if (entry.editable) {
      var edit = document.createElement('button');
      edit.type = 'button';
      edit.textContent = 'Edit';
      edit.setAttribute('aria-label', 'Edit ' + entry.label);
      edit.addEventListener('click', function () { openEditor(entry.kind, entry); });
      li.appendChild(edit);
    }
    return li;
  }

  function render(state) {
    [['sources', state.sources, false], ['guides', state.guides, true]].forEach(function (group) {
      var list = el(group[0]);
      var entries = Array.isArray(group[1]) ? group[1] : [];
      list.textContent = '';
      entries.forEach(function (entry) { list.appendChild(item(entry, group[2])); });
      el(group[0] + 'Empty').hidden = entries.length > 0;
    });
  }

  function setKind(kind) {
    current.kind = kind;
    Array.prototype.forEach.call(el('kinds').querySelectorAll('button'), function (button) {
      button.setAttribute('aria-pressed', String(button.getAttribute('data-kind') === kind));
    });
    var editing = !!current.entry;
    var keep = editing ? ' Leave blank to keep the current address' + (current.entry.host ? ' (' + current.entry.host + ').' : '.') : '';
    var labels = { m3u: 'Playlist address', xtream: 'Server address', stalker: 'Portal address', guide: 'Guide address' };
    var hints = {
      m3u: 'The full link to your M3U playlist.',
      xtream: 'Only the server, for example http://example.com:8080. Leave out player_api.php and anything after a question mark.',
      stalker: 'The portal link from your provider, for example http://example.com/c/.',
      guide: 'A link to an XMLTV file, for example https://example.com/guide.xml.gz.'
    };
    el('addressLabel').textContent = labels[kind];
    el('address').placeholder = editing ? 'Unchanged' : '';
    el('addressHint').textContent = hints[kind] + keep;
    el('xtreamFields').hidden = kind !== 'xtream';
    el('stalkerFields').hidden = kind !== 'stalker';
    el('credentialHint').hidden = !editing;
    el('username').placeholder = editing ? 'Unchanged' : '';
    el('password').placeholder = editing ? 'Unchanged' : '';
    el('mac').placeholder = editing ? 'Unchanged' : '00:1A:79:00:00:00';
    el('macHint').textContent = 'Six pairs separated by colons, as registered with your provider.' + (editing ? ' Leave blank to keep the saved one.' : '');
  }

  function openEditor(kind, entry) {
    current = { kind: kind, entry: entry || null };
    el('entryForm').reset();
    revealPassword(false);
    setText('formError', '');
    var guide = kind === 'guide';
    el('formTitle').textContent = entry ? 'Change ' + entry.label : (guide ? 'Add a guide' : 'Add a source');
    el('formHint').textContent = entry ? 'Only what you fill in changes. Your TV will ask you to confirm.' : 'Your TV will ask you to confirm before anything is saved.';
    el('kinds').hidden = guide || !!entry;
    el('label').value = entry ? entry.label : '';
    setKind(kind);
    show('editor');
    el('label').focus();
  }

  function revealPassword(on) {
    el('password').type = on ? 'text' : 'password';
    el('reveal').textContent = on ? 'Hide' : 'Show';
    el('reveal').setAttribute('aria-pressed', String(on));
  }

  var fieldErrors = {
    label: 'Enter a name of up to 240 characters.',
    username: 'Enter the username from your provider.',
    password: 'Enter the password from your provider.',
    mac: 'Enter the MAC address as six pairs separated by colons, such as 00:1A:79:12:34:56.',
    id: 'That entry can no longer be changed from here.',
    kind: 'Choose a source type.',
    body: 'Something went wrong with that request. Reload the page and try again.'
  };
  var addressErrors = {
    m3u: 'Enter a web address starting with http:// or https://.',
    guide: 'Enter a web address starting with http:// or https://. Files on the TV can only be added on the TV.',
    xtream: 'Enter only the server address, such as http://example.com:8080, without player_api.php or anything after a question mark.',
    stalker: 'Enter the portal address, such as http://example.com/c/.'
  };

  function submit(event) {
    event.preventDefault();
    var body = { kind: current.kind, label: el('label').value.trim(), address: el('address').value.trim() };
    if (current.entry) body.id = current.entry.id;
    if (current.kind === 'xtream') { body.username = el('username').value.trim(); body.password = el('password').value; }
    if (current.kind === 'stalker') body.mac = el('mac').value.trim();
    if (!body.label) { setText('formError', fieldErrors.label); return; }
    if (!current.entry && !body.address) { setText('formError', addressErrors[current.kind]); return; }
    el('send').disabled = true;
    api('POST', 'api/changes', body).then(function (result) {
      el('send').disabled = false;
      el('password').value = '';
      body = null;
      if (common(result)) return;
      if (result.status === 202 && result.data.id) { waitFor(result.data.id); return; }
      var error = result.data.error;
      if (error === 'invalid') setText('formError', result.data.field === 'address' ? addressErrors[current.kind] : (fieldErrors[result.data.field] || fieldErrors.body));
      else if (error === 'unchanged') setText('formError', 'Nothing has changed. Fill in what you want to change.');
      else if (error === 'busy') setText('formError', 'The TV is still waiting for you to confirm or reject another change.');
      else if (error === 'missing') setText('formError', 'That entry no longer exists on the TV.');
      else if (error === 'locked') setText('formError', fieldErrors.id);
      else setText('formError', 'The TV couldn\'t take that request. Nothing was saved.');
    }, function () { el('send').disabled = false; el('password').value = ''; lost(); });
  }

  function progress(state, title, text) {
    var box = el('progress');
    box.className = 'card status' + (state === 'ok' ? ' ok' : state === 'no' ? ' no' : '');
    el('progressTitle').textContent = title;
    el('progressText').textContent = text;
    el('done').hidden = state === 'wait';
    show('progress');
  }

  function stopPolling() { if (polling) { clearTimeout(polling); polling = null; } }

  function waitFor(id) {
    var guide = current.kind === 'guide';
    var misses = 0;
    progress('wait', 'Check your TV', 'Your TV is asking whether to save this. Choose Save or Reject on the TV.');
    function poll() {
      api('GET', 'api/changes/' + encodeURIComponent(id)).then(function (result) {
        var status = result.data.status;
        if ((result.status === 200 && status === 'pending') || result.status === 429 || result.status >= 500) { polling = setTimeout(poll, 1500); return; }
        polling = null;
        if (common(result)) return;
        if (status === 'saved') progress('ok', 'Saved on the TV', guide ? 'The TV is loading the guide now.' : 'The TV is loading the channels now.');
        else if (status === 'rejected') progress('no', 'Rejected on the TV', 'Nothing was saved.');
        else if (status === 'failed') progress('no', 'The TV couldn\'t save this', 'Nothing was saved. Check the details and try again.');
        else progress('no', 'The TV no longer has this change', 'Check the TV to see whether it was saved.');
      }, function () {
        misses += 1;
        if (misses >= 3) lost(); else polling = setTimeout(poll, 1500);
      });
    }
    polling = setTimeout(poll, 1000);
  }

  el('pairForm').addEventListener('submit', function (event) {
    event.preventDefault();
    var code = el('code').value.replace(/\D/g, '');
    if (code.length !== 6) { setText('pairError', 'Enter all 6 digits.'); return; }
    el('pairButton').disabled = true;
    api('POST', 'api/pair', { code: code }).then(function (result) {
      el('pairButton').disabled = false;
      el('code').value = '';
      if (result.status === 200) { setText('pairError', ''); load(); return; }
      if (result.status === 410) { ended('Too many wrong codes', 'For safety this link has been turned off. Scan the new QR code on your TV.'); return; }
      if (common(result)) return;
      if (result.data.error === 'code') {
        var left = result.data.attemptsLeft;
        setText('pairError', 'That code didn\'t match. ' + (left === 1 ? '1 try left.' : left + ' tries left.'));
        return;
      }
      setText('pairError', 'The TV couldn\'t check that code. Try again.');
    }, function () { el('pairButton').disabled = false; lost(); });
  });
  el('code').addEventListener('input', function () { this.value = this.value.replace(/\D/g, '').slice(0, 6); });
  el('addSource').addEventListener('click', function () { openEditor('m3u', null); });
  el('addGuide').addEventListener('click', function () { openEditor('guide', null); });
  Array.prototype.forEach.call(el('kinds').querySelectorAll('button'), function (button) {
    button.addEventListener('click', function () { setKind(button.getAttribute('data-kind')); setText('formError', ''); });
  });
  el('reveal').addEventListener('click', function () { revealPassword(el('password').type === 'password'); });
  el('entryForm').addEventListener('submit', submit);
  el('cancel').addEventListener('click', function () { el('password').value = ''; load(); });
  el('done').addEventListener('click', load);
  load();
})();
</script>
</body>
</html>
"""

    private const val ENDED = HEAD + """<body>
<main>
<header><div class="mark" aria-hidden="true">N</div><div><h1>Live TV setup</h1><p class="sub">Nuvio</p></div></header>
<section class="card status no">
<div class="dot"></div>
<p class="big">This setup link has ended</p>
<p class="hint">Open Live TV setup on your TV and scan the QR code it shows. Each link works only while the setup screen is open.</p>
</section>
</main>
</body>
</html>
"""
}
