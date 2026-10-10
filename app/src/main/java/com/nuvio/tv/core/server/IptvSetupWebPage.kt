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
<meta name="theme-color" content="#080a0c">
<link rel="icon" href="data:,">
<title>Nuvio · Live TV setup</title>
<style nonce="NONCE_VALUE">
:root{--bg:#080a0c;--surface:#14181c;--raised:#1b2026;--field:#222222;--line:#2d3238;--line-strong:#3a4047;--text:#ffffff;--soft:#dce2e7;--muted:#b3b3b3;--faint:#8a9097;--accent:#d5dde3;--accent-hi:#f3f6f8;--ink:#111111;--good:#4caf50;--bad:#cf6679;--warn:#ffb74d;--r:20px;--rs:12px}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%;text-size-adjust:100%}
html,body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif}
body{min-height:100vh;background:radial-gradient(900px 520px at 85% -10%,rgba(213,221,227,.08),transparent 60%),radial-gradient(700px 420px at -10% 0%,rgba(74,79,89,.22),transparent 60%),var(--bg)}
main{max-width:1080px;margin:0 auto;padding:max(20px,env(safe-area-inset-top)) max(16px,env(safe-area-inset-right)) 56px max(16px,env(safe-area-inset-left))}
.top{display:flex;align-items:center;gap:14px;margin:4px 0 20px}
.mark{flex:none;width:44px;height:44px;border-radius:14px;display:grid;place-items:center;background:linear-gradient(145deg,#f3f6f8,#9aa6b0);color:var(--ink);box-shadow:0 6px 20px rgba(0,0,0,.4)}
.mark svg{width:22px;height:22px}
.brand{flex:1;min-width:0}
.brand b{display:block;font-size:13px;font-weight:700;letter-spacing:.14em;text-transform:uppercase;color:var(--muted)}
h1{font-size:24px;line-height:1.2;margin:0;font-weight:700;letter-spacing:-.01em}
.chip{flex:none;display:inline-flex;align-items:center;gap:8px;font-size:13px;color:var(--soft);background:rgba(255,255,255,.06);border:1px solid var(--line);border-radius:999px;padding:6px 12px}
.chip::before{content:"";width:8px;height:8px;border-radius:50%;background:var(--good);box-shadow:0 0 0 3px rgba(76,175,80,.2)}
h2{font-size:18px;line-height:1.3;margin:0;font-weight:650}
h3{font-size:13px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:var(--faint);margin:22px 0 4px}
h3:first-of-type{margin-top:14px}
.card{background:linear-gradient(180deg,rgba(255,255,255,.035),rgba(255,255,255,.01)),var(--surface);border:1px solid rgba(255,255,255,.07);border-radius:var(--r);padding:20px;margin-bottom:16px;box-shadow:0 10px 30px rgba(0,0,0,.25)}
.head{display:flex;align-items:flex-start;gap:12px}
.head .grow p{margin:2px 0 0}
.note{display:flex;gap:10px;align-items:flex-start;font-size:14px;color:var(--soft);border:1px solid rgba(255,183,77,.28);background:rgba(255,183,77,.07);border-radius:14px;padding:12px 14px;margin:0 0 16px}
.note svg{flex:none;width:18px;height:18px;margin-top:2px;color:var(--warn)}
.note strong{color:var(--warn);font-weight:650}
.banner{font-size:14px;color:var(--soft);background:rgba(213,221,227,.07);border:1px solid var(--line);border-radius:14px;padding:12px 14px;margin:0 0 16px}
.hint{font-size:13px;line-height:1.45;color:var(--faint);margin:6px 0 0}
.lead{font-size:14px;color:var(--muted);margin:4px 0 0}
label,legend{display:block;font-size:14px;font-weight:600;color:var(--soft);margin:16px 0 6px;padding:0}
fieldset{border:0;margin:0;padding:0;min-width:0}
input,select{width:100%;font:inherit;color:var(--text);background:var(--field);border:1px solid var(--line);border-radius:var(--rs);padding:12px 14px;outline:none;min-height:48px;transition:border-color .15s,box-shadow .15s}
input::placeholder{color:#6b7178}
input:focus,select:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(213,221,227,.18)}
input[aria-invalid="true"]{border-color:var(--bad)}
input.code{font-size:30px;letter-spacing:.4em;text-indent:.4em;text-align:center;font-variant-numeric:tabular-nums;font-weight:700;padding:14px}
.row{display:flex;gap:8px;align-items:stretch}
.row input{flex:1;min-width:0}
.pick{position:relative}
.pick select{appearance:none;-webkit-appearance:none;padding-right:42px;cursor:pointer}
.pick::after{content:"";position:absolute;right:18px;top:50%;width:8px;height:8px;border-right:2px solid var(--muted);border-bottom:2px solid var(--muted);transform:translateY(-70%) rotate(45deg);pointer-events:none}
button{font:inherit;font-weight:600;border-radius:999px;border:1px solid var(--line-strong);background:var(--raised);color:var(--text);padding:11px 18px;min-height:44px;cursor:pointer;transition:background .15s,transform .05s}
button:hover{background:#242a31}
button:active{transform:scale(.98)}
button.primary{background:var(--accent);border-color:var(--accent);color:var(--ink)}
button.primary:hover{background:var(--accent-hi)}
button.ghost{background:transparent;border-color:transparent;color:var(--muted)}
button.small{padding:7px 14px;min-height:36px;font-size:14px}
button.wide{width:100%;margin-top:18px}
button:disabled{opacity:.45;cursor:default;transform:none}
:focus-visible{outline:2px solid var(--accent-hi);outline-offset:2px}
input:focus-visible,select:focus-visible{outline:none}
.seg{display:grid;gap:4px;background:var(--field);border:1px solid var(--line);border-radius:14px;padding:4px}
.c2{grid-template-columns:repeat(2,1fr)}
.c3{grid-template-columns:repeat(3,1fr)}
.c4{grid-template-columns:repeat(2,1fr)}
.seg label{position:relative;margin:0;font-weight:500;font-size:14px;color:var(--muted)}
.seg input{position:absolute;opacity:0;inset:0;width:100%;height:100%;margin:0;min-height:0;cursor:pointer}
.seg span{display:flex;align-items:center;justify-content:center;text-align:center;min-height:40px;padding:8px 6px;border-radius:10px;line-height:1.25}
.seg input:checked+span{background:var(--accent);color:var(--ink);font-weight:650}
.seg input:focus-visible+span{outline:2px solid var(--accent-hi);outline-offset:1px}
.seg input:disabled+span{opacity:.4}
.seg input:disabled{cursor:default}
.seg button{border:0;background:transparent;color:var(--muted);border-radius:10px;min-height:40px;padding:8px 6px;font-size:14px;font-weight:500}
.seg button[aria-pressed="true"]{background:var(--accent);color:var(--ink);font-weight:650}
.toggle{display:flex;align-items:center;gap:14px;padding:12px 0;border-top:1px solid var(--line)}
.toggle label{margin:0;cursor:pointer}
.toggle .hint{margin-top:2px}
input.switch{appearance:none;-webkit-appearance:none;flex:none;width:52px;height:32px;min-height:0;padding:0;border-radius:999px;background:var(--line-strong);border:0;position:relative;cursor:pointer;transition:background .2s}
input.switch::before{content:"";position:absolute;top:4px;left:4px;width:24px;height:24px;border-radius:50%;background:#f3f6f8;box-shadow:0 1px 3px rgba(0,0,0,.4);transition:transform .2s}
input.switch:checked{background:var(--good)}
input.switch:checked::before{transform:translateX(20px)}
input.switch:focus{box-shadow:0 0 0 3px rgba(213,221,227,.25)}
.pair2{display:grid;grid-template-columns:1fr;gap:0 12px}
.list{list-style:none;margin:14px 0 0;padding:0}
.list li{display:flex;align-items:center;gap:12px;padding:12px;border-radius:14px;background:rgba(255,255,255,.025);border:1px solid rgba(255,255,255,.05);margin-top:8px}
.list li .tools{display:flex;gap:6px;flex:none}
.list li.picked{border-color:var(--accent)}
.badge{flex:none;width:42px;height:42px;border-radius:12px;display:grid;place-items:center;font-size:11px;font-weight:800;letter-spacing:.04em;color:var(--soft);background:var(--raised);border:1px solid var(--line)}
.grow{flex:1;min-width:0}
.name{font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.meta{font-size:13px;color:var(--faint);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.empty{color:var(--faint);font-size:14px;margin:14px 0 0;padding:16px;border:1px dashed var(--line-strong);border-radius:14px;text-align:center}
.actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:16px}
.center{justify-content:center}
.error{color:var(--bad);font-size:14px;margin:12px 0 0;padding:10px 12px;border-radius:12px;background:rgba(207,102,121,.1);border:1px solid rgba(207,102,121,.3)}
.savebar{position:sticky;bottom:max(12px,env(safe-area-inset-bottom));display:flex;align-items:center;gap:8px;margin-top:18px;padding:10px 10px 10px 16px;border-radius:999px;background:rgba(27,32,38,.94);border:1px solid var(--line-strong);box-shadow:0 10px 30px rgba(0,0,0,.45)}
.savebar .grow{font-size:14px;color:var(--soft);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.status{text-align:center;padding:36px 20px}
.status .big{font-size:22px;font-weight:700;margin:16px 0 4px}
.status .hint{font-size:15px;color:var(--muted);max-width:420px;margin-left:auto;margin-right:auto}
.orb{width:64px;height:64px;margin:0 auto;border-radius:50%;display:grid;place-items:center;background:rgba(213,221,227,.1);color:var(--accent)}
.orb svg{width:30px;height:30px}
.orb .i-ok,.orb .i-no{display:none}
.wait .orb{animation:pulse 1.4s ease-in-out infinite}
.ok .orb{background:rgba(76,175,80,.14);color:var(--good)}
.no .orb{background:rgba(207,102,121,.14);color:var(--bad)}
.ok .i-tv,.no .i-tv{display:none}
.ok .i-ok,.no .i-no{display:block}
@keyframes pulse{0%,100%{box-shadow:0 0 0 0 rgba(213,221,227,.25)}50%{box-shadow:0 0 0 14px rgba(213,221,227,0)}}
.narrow{max-width:560px;margin-left:auto;margin-right:auto}
a.btn{display:inline-flex;align-items:center;justify-content:center;font-weight:600;font-size:14px;line-height:1.2;border-radius:999px;border:1px solid var(--line-strong);background:var(--raised);color:var(--text);padding:7px 14px;min-height:36px;text-decoration:none;white-space:nowrap}
a.btn:hover{background:#242a31}
a.btn.primary{background:var(--accent);border-color:var(--accent);color:var(--ink)}
a.btn.primary:hover{background:var(--accent-hi)}
.list li.rec{flex-wrap:wrap}
.list li.rec .grow{flex:1 1 160px}
.list li.rec .tools{flex:1 1 100%;justify-content:flex-end}
.rec .off{font-size:13px;color:var(--warn)}
.gap{margin-top:14px}
#savedTitle:focus{outline:none}
[hidden]{display:none!important}
footer{color:var(--faint);font-size:12px;text-align:center;margin-top:28px}
@media (min-width:560px){.list li.rec{flex-wrap:nowrap}.list li.rec .tools{flex:none}.c4{grid-template-columns:repeat(4,1fr)}.pair2{grid-template-columns:1fr 1fr}main{padding-top:32px}.card{padding:24px}}
@media (min-width:900px){#home:not([hidden]){display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:16px;align-items:start}#home .card{margin-bottom:0}#home .col{display:grid;gap:16px}#home .banner,#home #savedCard{grid-column:1/-1;margin:0}}
@media (prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}
</style>
</head>
"""

    private const val MARK = """<div class="mark" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="5" width="20" height="13" rx="3"/><path d="M8 21h8M9 9.5v5l4.5-2.5z"/></svg></div>"""

    private const val ORB = """<div class="orb" aria-hidden="true"><svg class="i-tv" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="5" width="20" height="13" rx="3"/><path d="M8 21h8"/></svg><svg class="i-ok" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg><svg class="i-no" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M7 7l10 10M17 7L7 17"/></svg></div>"""

    private const val PAGE = HEAD + """<body>
<main>
<header class="top">""" + MARK + """<div class="brand"><b>Nuvio</b><h1>Live TV setup</h1></div><span id="connected" class="chip" hidden>Connected to TV</span></header>
<p class="note" role="note"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3l9 16H3z"/><path d="M12 10v4M12 17h.01"/></svg><span><strong>Home network only.</strong> This page talks to your TV over your local network without encryption. Don't use it on public or shared Wi-Fi.</span></p>

<section id="pair" class="card narrow" hidden aria-labelledby="pairTitle">
<h2 id="pairTitle">Enter the code on your TV</h2>
<p class="lead">Your TV shows a 6-digit pairing code next to the QR code.</p>
<form id="pairForm" method="post" novalidate>
<label for="code">Pairing code</label>
<input id="code" class="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="[0-9]{6}" placeholder="000000" required aria-describedby="pairError">
<p id="pairError" class="error" role="alert" hidden></p>
<button class="primary wide" type="submit" id="pairButton">Connect</button>
</form>
</section>

<section id="home" hidden>
<p id="busy" class="banner" role="status" hidden>Your TV is waiting for you to save or reject another change.</p>
<div id="savedCard" class="card" aria-labelledby="savedTitle" hidden>
<div class="head"><div class="grow"><h2 id="savedTitle" tabindex="-1"></h2><p class="lead" id="savedText" role="status"></p></div><button type="button" class="ghost small" id="savedClose">Close</button></div>
<div class="actions"><button class="primary" type="button" id="savedGuide" hidden></button><button type="button" id="savedGuides" hidden></button><button type="button" id="savedMore"></button></div>
</div>
<div class="col">
<div class="card" id="profileCard" aria-labelledby="profileTitle" hidden>
<div class="head"><div class="grow"><h2 id="profileTitle">Profile</h2><p class="lead" id="profileLead"></p></div></div>
<label for="profileChoice">Edit the Live TV setup of</label>
<div class="row"><div class="pick grow"><select id="profileChoice"></select></div><button type="button" id="switchProfile">Switch</button></div>
<p class="hint">Profiles locked with a PIN can only be changed on the TV.</p>
<p id="profileError" class="error" role="alert" hidden></p>
</div>
<div class="card" aria-labelledby="sourcesTitle">
<div class="head"><div class="grow"><h2 id="sourcesTitle">Sources</h2><p class="lead">Playlists and accounts that provide your channels.</p></div></div>
<ul id="sources" class="list" aria-labelledby="sourcesTitle"></ul>
<p id="sourcesEmpty" class="empty" hidden>No sources yet. Add an M3U playlist, Xtream account or Stalker portal.</p>
<div class="actions"><button class="primary" type="button" id="addSource">Add a source</button></div>
</div>
<div class="card" aria-labelledby="guidesTitle">
<div class="head"><div class="grow"><h2 id="guidesTitle">Programme guides</h2><p class="lead">XMLTV guides that fill in what's on.</p></div></div>
<ul id="guides" class="list" aria-labelledby="guidesTitle"></ul>
<p id="guidesEmpty" class="empty" hidden>No guides yet. Xtream and Stalker sources usually bring their own.</p>
<div class="actions"><button type="button" id="addGuide">Add a guide</button></div>
<p class="hint">To remove a source or guide, use the TV. Use Guides next to a source to choose its guides and their order.</p>
</div>
<div class="card" aria-labelledby="recordingsTitle">
<div class="head"><div class="grow"><h2 id="recordingsTitle">Recordings</h2><p class="lead">Download recordings to this phone or tablet, or watch them in VLC.</p></div></div>
<div class="actions"><button type="button" id="openRecordings">Show recordings</button></div>
</div>
</div>

<form id="settings" class="card" novalidate aria-labelledby="settingsTitle" hidden>
<div class="head"><div class="grow"><h2 id="settingsTitle">Live TV settings</h2><p class="lead">These apply to Live TV on this TV. Your TV asks you to confirm any change.</p></div></div>

<h3>Playback</h3>
<fieldset>
<legend>Stream format</legend>
<div class="seg c3">
<label><input type="radio" name="format" value="auto"><span>Auto</span></label>
<label><input type="radio" name="format" value="hls"><span>HLS</span></label>
<label><input type="radio" name="format" value="mpegts"><span>MPEG-TS</span></label>
</div>
<p class="hint">Used for channels set to Auto. Try HLS or MPEG-TS if channels don't play.</p>
</fieldset>
<div class="toggle"><div class="grow"><label for="timeshift">Pause and rewind with catch-up</label><p class="hint" id="timeshiftHint">On channels with catch-up, carry on from where you paused and rewind through the provider's archive.</p></div><input type="checkbox" role="switch" id="timeshift" class="switch" aria-describedby="timeshiftHint"></div>

<h3>Menu</h3>
<div class="toggle"><div class="grow"><label for="sport">Show Sport in the menu</label><p class="hint" id="sportHint">Channels with sport on now or in the next six hours, from the programme guide.</p></div><input type="checkbox" role="switch" id="sport" class="switch" aria-describedby="sportHint"></div>
<fieldset>
<legend>Live TV opens on</legend>
<div class="seg c4">
<label><input type="radio" name="startView" value="last"><span>Last category</span></label>
<label><input type="radio" name="startView" value="all"><span>All channels</span></label>
<label><input type="radio" name="startView" value="favourites"><span>Favourites</span></label>
<label><input type="radio" name="startView" value="sport"><span>Sport</span></label>
</div>
<p class="hint" id="startHint"></p>
</fieldset>

<div id="multiview">
<h3>Multiview</h3>
<fieldset>
<legend>Layout</legend>
<div class="seg c2">
<label><input type="radio" name="layout" value="grid"><span>Grid</span></label>
<label><input type="radio" name="layout" value="focus"><span>One large</span></label>
<label><input type="radio" name="layout" value="sidebyside"><span>Side by side</span></label>
<label><input type="radio" name="layout" value="oneovertwo"><span>One over two</span></label>
</div>
</fieldset>
<fieldset>
<legend>Picture quality</legend>
<div class="seg c3">
<label><input type="radio" name="quality" value="auto"><span>Automatic</span></label>
<label><input type="radio" name="quality" value="sharpest"><span>Sharpest</span></label>
<label><input type="radio" name="quality" value="lightest"><span>Lightest</span></label>
</div>
</fieldset>
<p class="hint" id="qualityHint"></p>
</div>

<h3>Recordings</h3>
<div class="pair2">
<div><label for="recordEarly">Start recordings early</label>
<div class="pick"><select id="recordEarly"><option value="0">None</option><option value="1">1 minute</option><option value="2">2 minutes</option><option value="5">5 minutes</option><option value="10">10 minutes</option></select></div></div>
<div><label for="recordLate">Keep recording after the end</label>
<div class="pick"><select id="recordLate" aria-describedby="lateHint"><option value="0">None</option><option value="2">2 minutes</option><option value="5">5 minutes</option><option value="10">10 minutes</option><option value="15">15 minutes</option><option value="30">30 minutes</option></select></div></div>
</div>
<p class="hint" id="lateHint">Guide times are often a little out. Applies to new recordings.</p>

<p id="settingsError" class="error" role="alert" hidden></p>
<div class="savebar" id="savebar" hidden><span class="grow" id="changeCount" role="status"></span><button type="button" class="ghost small" id="undo">Undo</button><button type="submit" class="primary small" id="sendSettings">Send to TV</button></div>
</form>
</section>

<section id="editor" class="card narrow" hidden aria-labelledby="formTitle">
<h2 id="formTitle">Add a source</h2>
<p id="formHint" class="lead"></p>
<div id="kinds" class="seg c3" role="group" aria-label="Source type">
<button type="button" data-kind="m3u">M3U playlist</button>
<button type="button" data-kind="xtream">Xtream</button>
<button type="button" data-kind="stalker">Stalker</button>
</div>
<form id="entryForm" method="post" novalidate autocomplete="off">
<label for="label">Name</label>
<input id="label" maxlength="240" autocomplete="off" required placeholder="For example, Home playlist">
<label for="address" id="addressLabel">Playlist address</label>
<input id="address" type="url" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" maxlength="16384" aria-describedby="addressHint">
<p id="addressHint" class="hint"></p>
<div id="xtreamFields" hidden>
<label for="username">Username</label>
<input id="username" autocapitalize="off" autocorrect="off" spellcheck="false" maxlength="4096" autocomplete="off">
<label for="password">Password</label>
<div class="row"><input id="password" type="password" maxlength="4096" autocomplete="new-password" aria-describedby="credentialHint"><button type="button" id="reveal" aria-pressed="false" aria-controls="password">Show</button></div>
<p id="credentialHint" class="hint" hidden>Leave blank to keep what's saved on the TV. If you change the server address, enter both again.</p>
</div>
<div id="stalkerFields" hidden>
<label for="mac">MAC address</label>
<input id="mac" autocapitalize="characters" autocorrect="off" spellcheck="false" maxlength="17" placeholder="00:1A:79:00:00:00" aria-describedby="macHint">
<p id="macHint" class="hint">Six pairs separated by colons, as registered with your provider.</p>
</div>
<div id="guideForField" hidden>
<label for="guideFor">Use it for</label>
<div class="pick"><select id="guideFor" aria-describedby="guideForHint"></select></div>
<p id="guideForHint" class="hint">The source whose channels this guide fills in. You can change this later with Guides next to a source.</p>
</div>
<p id="formError" class="error" role="alert" hidden></p>
<button class="primary wide" type="submit" id="send">Send to TV</button>
<button class="wide ghost" type="button" id="cancel">Cancel</button>
</form>
</section>

<section id="assign" class="narrow" hidden aria-labelledby="assignTitle">
<div class="card">
<div class="head"><div class="grow"><h2 id="assignTitle">Guides</h2><p class="lead">The TV uses the first guide that has a channel, then the next.</p></div></div>
<h3>In use, first to last</h3>
<ul id="linked" class="list"></ul>
<p id="linkedEmpty" class="empty" hidden>No guides in use for this source.</p>
<h3>Other guides</h3>
<ul id="unlinked" class="list"></ul>
<p id="unlinkedEmpty" class="empty" hidden>No other guides. Add one from the main page.</p>
<p id="linksError" class="error" role="alert" hidden></p>
<div class="savebar" id="linksBar" hidden><span class="grow" role="status">Guide order changed</span><button type="button" class="ghost small" id="linksUndo">Undo</button><button type="button" class="primary small" id="sendLinks">Send to TV</button></div>
</div>
<div class="card" aria-labelledby="channelTitle">
<div class="head"><div class="grow"><h2 id="channelTitle">Guide for one channel</h2><p class="lead">Find a channel, then pick the guide channel it should show.</p></div></div>
<label for="channelQuery">Channel</label>
<input id="channelQuery" type="search" autocomplete="off" autocapitalize="off" spellcheck="false" maxlength="64" placeholder="Search channels">
<ul id="channelResults" class="list"></ul>
<p id="channelEmpty" class="empty" hidden>No channels found.</p>
<div id="channelPanel" hidden>
<p class="banner" id="channelNow" role="status"></p>
<label for="guideFeed">Guide</label>
<div class="pick"><select id="guideFeed"></select></div>
<label for="guideQuery">Guide channel</label>
<input id="guideQuery" type="search" autocomplete="off" autocapitalize="off" spellcheck="false" maxlength="64" placeholder="Search the guide">
<ul id="guideResults" class="list"></ul>
<p id="guideEmpty" class="empty" hidden>No guide channels found.</p>
<div class="actions"><button type="button" id="automatic">Match automatically</button></div>
</div>
<p id="channelError" class="error" role="alert" hidden></p>
</div>
<button class="wide ghost" type="button" id="assignBack">Back</button>
</section>

<section id="recordings" class="narrow" hidden aria-labelledby="recTitle">
<div class="card">
<div class="head"><div class="grow"><h2 id="recTitle">Recordings</h2><p class="lead">Finished recordings of the profile this page changes.</p></div><button type="button" class="small" id="recRefresh">Refresh</button></div>
<p class="banner gap" id="recNote">Phone and tablet browsers can't play this video format. Download a recording and open it in a video player app. Keep Live TV setup open on your TV until the download finishes.</p>
<p id="recLoading" class="hint" role="status" hidden>Loading recordings…</p>
<ul id="recList" class="list" aria-labelledby="recTitle"></ul>
<p id="recEmpty" class="empty" hidden>No finished recordings yet.</p>
<p id="recError" class="error" role="alert" hidden></p>
</div>
<button class="wide ghost" type="button" id="recBack">Back</button>
</section>

<section id="progress" class="card status narrow wait" hidden>
""" + ORB + """
<p class="big" id="progressTitle" role="status">Check your TV</p>
<p class="hint" id="progressText">Confirm the change on your TV to save it.</p>
<div class="actions center"><button class="primary" type="button" id="done" hidden>Back to setup</button></div>
</section>

<section id="ended" class="card status narrow no" hidden>
""" + ORB + """
<p class="big" id="endedTitle">This setup link has ended</p>
<p class="hint" id="endedText">Open Live TV setup on your TV and scan the new QR code.</p>
</section>

<footer>Nuvio · works only while the setup screen is open on your TV</footer>
</main>
<script nonce="NONCE_VALUE">
(function () {
  'use strict';
  var base = location.pathname.replace(/[^\/]*$/, '');
  var views = ['pair', 'home', 'editor', 'assign', 'recordings', 'progress', 'ended'];
  var kindNames = { m3u: 'M3U playlist', xtream: 'Xtream account', stalker: 'Stalker portal', guide: 'XMLTV guide' };
  var badges = { m3u: 'M3U', xtream: 'XT', stalker: 'STB', guide: 'EPG' };
  var settingKeys = ['format', 'timeshift', 'sport', 'startView', 'layout', 'quality', 'recordEarly', 'recordLate'];
  var current = null;
  var saved = null;
  var polling = null;
  var listing = null;
  var assign = null;
  var timers = {};
  var just = null;

  function el(id) { return document.getElementById(id); }
  function show(name) {
    views.forEach(function (v) { el(v).hidden = v !== name; });
    el('connected').hidden = name === 'pair' || name === 'ended';
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

  function common(result, errorId) {
    if (result.status === 401) { askForCode('Your session has ended. Enter the code shown on your TV.'); return true; }
    if (result.status === 404 && result.data.error === 'link') { ended(); return true; }
    if (result.status === 429) { setText(errorId, 'Too many requests. Wait a minute, then try again.'); return true; }
    return false;
  }

  function askForCode(message) {
    stopPolling();
    el('code').value = '';
    setText('pairError', message);
    show('pair');
    el('code').focus();
  }

  function load() {
    var profile = listing ? listing.profile : null;
    var edits = saved && !el('settings').hidden ? settingsDiff().values : null;
    return api('GET', 'api/state').then(function (result) {
      if (common(result, 'pairError')) return false;
      if (result.status !== 200) { lost(); return false; }
      render(result.data);
      if (profile !== null && listing.profile !== profile) just = null;
      return api('GET', 'api/settings').then(function (answer) {
        if (common(answer, 'pairError')) return false;
        if (answer.status === 200) {
          renderSettings(answer.data);
          if (edits && listing.profile === profile) keepSettings(edits);
        } else el('settings').hidden = true;
        renderSaved();
        show('home');
        return true;
      });
    }).catch(function () { lost(); return false; });
  }

  function findEntry(group, id) {
    return ((listing && listing[group]) || []).filter(function (e) { return e.id === id; })[0] || null;
  }

  function renderSaved() {
    var card = el('savedCard');
    if (!just) { card.hidden = true; return; }
    var guide = just.kind === 'guide';
    var entry = just.id ? findEntry(guide ? 'guides' : 'sources', just.id) : null;
    var source = guide ? (just.source ? findEntry('sources', just.source) : null) : entry;
    var label = entry ? entry.label : just.label;
    el('savedTitle').textContent = just.edit ? 'Saved changes to ' + label : 'Saved ' + label + ' on the TV';
    el('savedText').textContent = guide
      ? 'The TV is loading the guide now.' + (source ? ' It fills in the channels of ' + source.label + '.' : '')
      : 'The TV is loading the channels now.' + (just.edit ? '' : ' If your provider gave you a separate XMLTV guide link, add it here.');
    var add = el('savedGuide');
    add.hidden = !!guide || !source;
    add.textContent = 'Add a guide for this source';
    var pick = el('savedGuides');
    pick.hidden = !source;
    pick.textContent = 'Guides for ' + (source ? source.label : '');
    el('savedMore').textContent = guide ? 'Add another guide' : 'Add another source';
    card.hidden = false;
  }

  function ids(group) { return ((listing && listing[group]) || []).map(function (e) { return e.id; }); }

  function keepSettings(edits) {
    Object.keys(edits).forEach(function (key) {
      var value = edits[key];
      if (value === saved[key]) return;
      if (key === 'timeshift' || key === 'sport') el(key).checked = !!value;
      else if (key === 'recordEarly' || key === 'recordLate') el(key).value = String(value);
      else setRadio(key, value);
    });
    refreshSettings();
  }

  function item(entry, isGuide) {
    var li = document.createElement('li');
    var badge = document.createElement('span');
    badge.className = 'badge';
    badge.setAttribute('aria-hidden', 'true');
    badge.textContent = badges[entry.kind] || '';
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
    li.appendChild(badge);
    li.appendChild(text);
    if (!isGuide) {
      var guides = document.createElement('button');
      guides.type = 'button';
      guides.className = 'small';
      guides.textContent = 'Guides';
      guides.setAttribute('aria-label', 'Guides for ' + entry.label);
      guides.addEventListener('click', function () { openAssign(entry); });
      li.appendChild(guides);
    }
    if (entry.editable) {
      var edit = document.createElement('button');
      edit.type = 'button';
      edit.className = 'small';
      edit.textContent = 'Edit';
      edit.setAttribute('aria-label', 'Edit ' + entry.label);
      edit.addEventListener('click', function () { openEditor(entry.kind, entry); });
      li.appendChild(edit);
    }
    return li;
  }

  function render(state) {
    listing = state;
    renderProfiles(state);
    [['sources', state.sources, false], ['guides', state.guides, true]].forEach(function (group) {
      var list = el(group[0]);
      var entries = Array.isArray(group[1]) ? group[1] : [];
      list.textContent = '';
      entries.forEach(function (entry) { list.appendChild(item(entry, group[2])); });
      list.hidden = entries.length === 0;
      el(group[0] + 'Empty').hidden = entries.length > 0;
    });
    el('busy').hidden = !state.pending;
  }

  function renderProfiles(state) {
    var profiles = Array.isArray(state.profiles) ? state.profiles : [];
    el('profileCard').hidden = profiles.length < 2;
    var select = el('profileChoice');
    select.textContent = '';
    var editing = null;
    profiles.forEach(function (p) {
      var option = document.createElement('option');
      option.value = String(p.id);
      option.textContent = p.locked && p.id !== state.profile ? p.name + ' (locked)' : p.name;
      option.disabled = p.locked && p.id !== state.profile;
      option.selected = p.id === state.profile;
      if (p.id === state.profile) editing = p.name;
      select.appendChild(option);
    });
    el('profileLead').textContent = editing ? 'Changes from this page go to ' + editing + '.' : '';
    setText('profileError', '');
  }

  function sendProfile() {
    var id = parseInt(el('profileChoice').value, 10);
    if (isNaN(id) || !listing || id === listing.profile) { setText('profileError', 'Choose another profile first.'); return; }
    el('switchProfile').disabled = true;
    api('POST', 'api/profile', { profile: id }).then(function (result) {
      el('switchProfile').disabled = false;
      if (common(result, 'profileError')) return;
      if (result.status === 202 && result.data.id) { waitFor(result.data.id, 'profile'); return; }
      if (result.data.error === 'locked') setText('profileError', 'That profile is locked with a PIN. Switch to it on the TV instead.');
      else refused(result.data.error, 'profileError');
    }, function () { el('switchProfile').disabled = false; lost(); });
  }

  function guideLabel(id) {
    var found = (listing && listing.guides || []).filter(function (g) { return g.id === id; })[0];
    return found ? found.label : 'Removed guide';
  }

  function openAssign(entry) {
    assign = { source: entry, saved: (entry.guides || []).slice(), order: (entry.guides || []).slice(), channel: null };
    el('assignTitle').textContent = 'Guides for ' + entry.label;
    el('channelQuery').value = '';
    el('channelResults').textContent = '';
    el('channelEmpty').hidden = true;
    el('channelPanel').hidden = true;
    setText('linksError', '');
    setText('channelError', '');
    renderAssign();
    show('assign');
    searchChannels();
  }

  function toolButton(text, label, disabled, action) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'small';
    button.textContent = text;
    button.setAttribute('aria-label', label);
    button.disabled = disabled;
    button.addEventListener('click', action);
    return button;
  }

  function guideRow(id, badge) {
    var li = document.createElement('li');
    var mark = document.createElement('span');
    mark.className = 'badge';
    mark.setAttribute('aria-hidden', 'true');
    mark.textContent = badge;
    var text = document.createElement('div');
    text.className = 'grow';
    var name = document.createElement('div');
    name.className = 'name';
    name.textContent = guideLabel(id);
    text.appendChild(name);
    li.appendChild(mark);
    li.appendChild(text);
    var tools = document.createElement('div');
    tools.className = 'tools';
    li.appendChild(tools);
    return { li: li, tools: tools, label: name.textContent };
  }

  function renderAssign() {
    var order = assign.order;
    var linked = el('linked');
    linked.textContent = '';
    order.forEach(function (id, index) {
      var row = guideRow(id, String(index + 1));
      row.tools.appendChild(toolButton('Up', 'Move ' + row.label + ' up', index === 0, function () { move(index, -1); }));
      row.tools.appendChild(toolButton('Down', 'Move ' + row.label + ' down', index === order.length - 1, function () { move(index, 1); }));
      row.tools.appendChild(toolButton('Remove', 'Stop using ' + row.label, false, function () { order.splice(index, 1); renderAssign(); }));
      linked.appendChild(row.li);
    });
    el('linkedEmpty').hidden = order.length > 0;
    var unlinked = el('unlinked');
    unlinked.textContent = '';
    var others = (listing.guides || []).filter(function (g) { return order.indexOf(g.id) < 0; });
    others.forEach(function (g) {
      var row = guideRow(g.id, 'EPG');
      row.tools.appendChild(toolButton('Use', 'Use ' + row.label, order.length >= 16, function () { order.push(g.id); renderAssign(); }));
      unlinked.appendChild(row.li);
    });
    el('unlinkedEmpty').hidden = others.length > 0;
    el('linksBar').hidden = order.join('|') === assign.saved.join('|');
    var feed = el('guideFeed');
    var chosen = feed.value;
    feed.textContent = '';
    assign.saved.forEach(function (id) {
      var option = document.createElement('option');
      option.value = id;
      option.textContent = guideLabel(id);
      option.selected = id === chosen;
      feed.appendChild(option);
    });
  }

  function move(index, step) {
    var order = assign.order;
    var item = order.splice(index, 1)[0];
    order.splice(index + step, 0, item);
    renderAssign();
  }

  function sendLinks() {
    setText('linksError', '');
    el('sendLinks').disabled = true;
    api('POST', 'api/links', { source: assign.source.id, guides: assign.order }).then(function (result) {
      el('sendLinks').disabled = false;
      if (common(result, 'linksError')) return;
      if (result.status === 202 && result.data.id) { waitFor(result.data.id, 'links'); return; }
      refused(result.data.error, 'linksError');
    }, function () { el('sendLinks').disabled = false; lost(); });
  }

  function later(name, action) {
    if (timers[name]) clearTimeout(timers[name]);
    timers[name] = setTimeout(action, 350);
  }

  function resultRow(title, detail, buttonText, label, action) {
    var li = document.createElement('li');
    var text = document.createElement('div');
    text.className = 'grow';
    var name = document.createElement('div');
    name.className = 'name';
    name.textContent = title;
    text.appendChild(name);
    if (detail) { var meta = document.createElement('div'); meta.className = 'meta'; meta.textContent = detail; text.appendChild(meta); }
    li.appendChild(text);
    li.appendChild(toolButton(buttonText, label, false, action));
    return li;
  }

  function searchChannels() {
    if (!assign) return;
    var query = el('channelQuery').value.trim();
    var source = assign.source.id;
    api('GET', 'api/channels?source=' + encodeURIComponent(source) + '&q=' + encodeURIComponent(query)).then(function (result) {
      if (!assign || assign.source.id !== source || el('channelQuery').value.trim() !== query) return;
      if (common(result, 'channelError')) return;
      if (result.status !== 200) { refused(result.data.error, 'channelError'); return; }
      var list = el('channelResults');
      list.textContent = '';
      var channels = Array.isArray(result.data.channels) ? result.data.channels : [];
      channels.forEach(function (ch) {
        var detail = ch.feed ? 'Guide: ' + guideLabel(ch.feed) + ' · ' + ch.guide : 'Guide: matched automatically';
        list.appendChild(resultRow(ch.name, detail, 'Choose', 'Choose ' + ch.name, function () { pickChannel(ch); }));
      });
      el('channelEmpty').hidden = channels.length > 0;
    }, lost);
  }

  function pickChannel(ch) {
    assign.channel = ch;
    el('channelNow').textContent = ch.name + ' · ' + (ch.feed ? 'now ' + guideLabel(ch.feed) + ' · ' + ch.guide : 'now matched automatically');
    el('channelPanel').hidden = false;
    el('guideQuery').value = ch.name;
    el('guideResults').textContent = '';
    el('guideEmpty').hidden = true;
    if (assign.saved.length === 0) setText('channelError', 'Send a guide order with at least one guide first.');
    else { setText('channelError', ''); searchGuide(); }
  }

  function searchGuide() {
    if (!assign || !assign.channel || !el('guideFeed').value) return;
    var feed = el('guideFeed').value;
    var query = el('guideQuery').value.trim();
    api('GET', 'api/guide-channels?feed=' + encodeURIComponent(feed) + '&q=' + encodeURIComponent(query)).then(function (result) {
      if (!assign || el('guideFeed').value !== feed || el('guideQuery').value.trim() !== query) return;
      if (common(result, 'channelError')) return;
      if (result.status !== 200) { refused(result.data.error, 'channelError'); return; }
      var list = el('guideResults');
      list.textContent = '';
      var channels = Array.isArray(result.data.channels) ? result.data.channels : [];
      channels.forEach(function (g) {
        list.appendChild(resultRow(g.name, g.id, 'Use', 'Use ' + g.name, function () { sendChannelGuide({ feed: feed, guide: g.id, guideName: g.name }); }));
      });
      el('guideEmpty').hidden = channels.length > 0;
    }, lost);
  }

  function sendChannelGuide(choice) {
    var body = { source: assign.source.id, channel: assign.channel.id };
    if (choice) { body.feed = choice.feed; body.guide = choice.guide; body.guideName = choice.guideName; }
    setText('channelError', '');
    api('POST', 'api/channel-guide', body).then(function (result) {
      if (common(result, 'channelError')) return;
      if (result.status === 202 && result.data.id) { waitFor(result.data.id, 'channel'); return; }
      refused(result.data.error, 'channelError');
    }, lost);
  }

  var recordingFile = /^api\/recordings\/[A-Za-z0-9-]{8,64}\/file\?e=[0-9]{1,18}&t=[0-9a-f]{64}$/;

  function openRecordings() {
    show('recordings');
    loadRecordings();
    el('recRefresh').focus();
  }

  function duration(ms) {
    var minutes = Math.round((ms || 0) / 60000);
    if (minutes < 1) return 'Under 1 min';
    var hours = Math.floor(minutes / 60);
    return hours ? hours + ' h' + (minutes % 60 ? ' ' + (minutes % 60) + ' min' : '') : minutes + ' min';
  }

  function size(bytes) {
    if (!(bytes > 0)) return '';
    if (bytes >= 1e9) return (bytes / 1e9).toFixed(1) + ' GB';
    if (bytes >= 1e6) return Math.round(bytes / 1e6) + ' MB';
    return Math.max(1, Math.round(bytes / 1e3)) + ' kB';
  }

  function when(ms) {
    var date = new Date(ms);
    if (isNaN(date.getTime())) return '';
    try { return date.toLocaleString(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' }); }
    catch (e) { return date.toLocaleString(); }
  }

  function link(text, label, href, primary, download) {
    var a = document.createElement('a');
    a.className = primary ? 'btn primary' : 'btn';
    a.textContent = text;
    a.href = href;
    a.setAttribute('aria-label', label);
    if (download) a.setAttribute('download', '');
    return a;
  }

  function recordingRow(r) {
    var li = document.createElement('li');
    li.className = 'rec';
    var mark = document.createElement('span');
    mark.className = 'badge';
    mark.setAttribute('aria-hidden', 'true');
    mark.textContent = 'REC';
    var text = document.createElement('div');
    text.className = 'grow';
    var name = document.createElement('div');
    name.className = 'name';
    name.textContent = r.title;
    var first = document.createElement('div');
    first.className = 'meta';
    first.textContent = [r.channel !== r.title ? r.channel : '', when(r.start)].filter(Boolean).join(' · ');
    var second = document.createElement('div');
    second.className = 'meta';
    second.textContent = [duration(r.duration), size(r.size), r.status === 'partial' ? 'Incomplete' : ''].filter(Boolean).join(' · ');
    text.appendChild(name);
    text.appendChild(first);
    text.appendChild(second);
    li.appendChild(mark);
    li.appendChild(text);
    var file = typeof r.file === 'string' && recordingFile.test(r.file) ? r.file : null;
    if (r.available && file) {
      var tools = document.createElement('div');
      tools.className = 'tools';
      tools.appendChild(link('Download', 'Download ' + r.title, base + file, true, true));
      li.appendChild(tools);
    } else {
      var off = document.createElement('div');
      off.className = 'off';
      off.textContent = 'Not available right now. Connect the drive or network folder it\'s saved on.';
      text.appendChild(off);
    }
    return li;
  }

  function loadRecordings() {
    setText('recError', '');
    el('recLoading').hidden = false;
    el('recRefresh').disabled = true;
    api('GET', 'api/recordings').then(function (result) {
      el('recLoading').hidden = true;
      el('recRefresh').disabled = false;
      if (common(result, 'recError')) return;
      var list = el('recList');
      list.textContent = '';
      if (result.status !== 200) { el('recEmpty').hidden = true; setText('recError', 'The TV couldn\'t list its recordings. Try again.'); return; }
      var items = Array.isArray(result.data.recordings) ? result.data.recordings : [];
      items.forEach(function (r) { if (r && typeof r.title === 'string') list.appendChild(recordingRow(r)); });
      list.hidden = items.length === 0;
      el('recEmpty').hidden = items.length > 0;
    }, function () { el('recLoading').hidden = true; el('recRefresh').disabled = false; lost(); });
  }

  function radios(name) { return Array.prototype.slice.call(el('settings').querySelectorAll('input[name="' + name + '"]')); }
  function radioValue(name) { var on = radios(name).filter(function (r) { return r.checked; })[0]; return on ? on.value : null; }
  function setRadio(name, value) { radios(name).forEach(function (r) { r.checked = r.value === value; }); }

  function renderSettings(values) {
    saved = values;
    ['format', 'startView', 'layout', 'quality'].forEach(function (key) { setRadio(key, values[key]); });
    el('timeshift').checked = !!values.timeshift;
    el('sport').checked = !!values.sport;
    el('recordEarly').value = String(values.recordEarly);
    el('recordLate').value = String(values.recordLate);
    el('multiview').hidden = values.multiview === false;
    el('settings').hidden = false;
    setText('settingsError', '');
    refreshSettings();
  }

  function readSettings() {
    return {
      format: radioValue('format'), timeshift: el('timeshift').checked, sport: el('sport').checked, startView: radioValue('startView'),
      layout: radioValue('layout'), quality: radioValue('quality'),
      recordEarly: minutes('recordEarly'), recordLate: minutes('recordLate')
    };
  }

  function minutes(id) { var value = parseInt(el(id).value, 10); return isNaN(value) ? null : value; }

  function settingsDiff() {
    var now = readSettings();
    var diff = {};
    var count = 0;
    settingKeys.forEach(function (key) {
      if (now[key] !== null && now[key] !== saved[key] && !(key === 'startView' && now[key] === 'sport' && !now.sport)) { diff[key] = now[key]; count += 1; }
    });
    return { values: diff, count: count };
  }

  var qualityHints = {
    auto: 'Matches each picture to its size on your TV and what the TV can decode.',
    sharpest: 'One step sharper where the TV can manage it. Uses more internet speed.',
    lightest: 'One step lower, for slower connections or busy TV boxes.'
  };

  function refreshSettings() {
    if (!saved) return;
    var sportOn = el('sport').checked;
    radios('startView').forEach(function (r) { if (r.value === 'sport') r.disabled = !sportOn && saved.startView !== 'sport'; });
    if (!sportOn && radioValue('startView') === 'sport' && saved.startView !== 'sport') setRadio('startView', saved.startView);
    el('startHint').textContent = sportOn ? 'Last category opens where you left off.' : 'Turn on Show Sport to open Live TV on Sport.';
    el('qualityHint').textContent = (qualityHints[radioValue('quality')] || '') + ' Applies to channels that offer more than one quality.';
    var diff = settingsDiff();
    el('savebar').hidden = diff.count === 0;
    el('changeCount').textContent = diff.count === 1 ? '1 change to send' : diff.count + ' changes to send';
  }

  function setKind(kind) {
    current.kind = kind;
    Array.prototype.forEach.call(el('kinds').querySelectorAll('button'), function (button) {
      button.setAttribute('aria-pressed', String(button.getAttribute('data-kind') === kind));
    });
    var editing = !!current.entry;
    var keep = editing ? ' Leave blank to keep the current address' + (current.entry.host ? ' (' + current.entry.host + ').' : '.') : '';
    var labels = { m3u: 'Playlist address', xtream: 'Server address', stalker: 'Portal address', guide: 'Guide address' };
    var examples = { m3u: 'https://example.com/playlist.m3u', xtream: 'http://example.com:8080', stalker: 'http://example.com/c/', guide: 'https://example.com/guide.xml.gz' };
    var hints = {
      m3u: 'The full link to your M3U playlist, for example https://example.com/playlist.m3u.',
      xtream: 'Only the server, for example http://example.com:8080. Leave out player_api.php and anything after a question mark.',
      stalker: 'The portal link from your provider, for example http://example.com/c/.',
      guide: 'A link to an XMLTV file, for example https://example.com/guide.xml.gz.'
    };
    el('addressLabel').textContent = labels[kind];
    el('address').placeholder = editing ? 'Unchanged' : examples[kind];
    el('addressHint').textContent = hints[kind] + keep;
    el('xtreamFields').hidden = kind !== 'xtream';
    el('stalkerFields').hidden = kind !== 'stalker';
    el('credentialHint').hidden = !editing;
    el('username').placeholder = editing ? 'Unchanged' : '';
    el('password').placeholder = editing ? 'Unchanged' : '';
    el('mac').placeholder = editing ? 'Unchanged' : '00:1A:79:00:00:00';
    el('macHint').textContent = 'Six pairs separated by colons, as registered with your provider.' + (editing ? ' Leave blank to keep the saved one. If you change the portal address, enter it again.' : '');
  }

  function guideTargets(preset) {
    var select = el('guideFor');
    var sources = (listing && listing.sources) || [];
    select.textContent = '';
    var none = document.createElement('option');
    none.value = '';
    none.textContent = sources.length ? 'Choose later' : 'No sources yet';
    select.appendChild(none);
    sources.forEach(function (s) {
      var option = document.createElement('option');
      option.value = s.id;
      option.textContent = s.label;
      select.appendChild(option);
    });
    select.value = preset ? preset.id : (sources.length === 1 ? sources[0].id : '');
    if (select.selectedIndex < 0) select.value = '';
    el('guideForField').hidden = sources.length === 0;
  }

  function openEditor(kind, entry, forSource) {
    current = { kind: kind, entry: entry || null };
    el('entryForm').reset();
    revealPassword(false);
    setText('formError', '');
    var guide = kind === 'guide';
    el('formTitle').textContent = entry ? 'Change ' + entry.label : (guide ? (forSource ? 'Add a guide for ' + forSource.label : 'Add a guide') : 'Add a source');
    el('formHint').textContent = entry ? 'Only what you fill in changes. Your TV will ask you to confirm.' : 'Your TV will ask you to confirm before anything is saved.';
    el('kinds').hidden = guide || !!entry;
    el('label').value = entry ? entry.label : '';
    if (guide && !entry) guideTargets(forSource); else el('guideForField').hidden = true;
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
    source: 'Choose the source again.',
    body: 'Something went wrong with that request. Reload the page and try again.'
  };
  var settingErrors = {
    startView: 'Turn on Show Sport to open Live TV on Sport.',
    layout: 'This TV can\'t show multiview.',
    quality: 'This TV can\'t show multiview.'
  };
  var addressErrors = {
    m3u: 'Enter a web address starting with http:// or https://.',
    guide: 'Enter a web address starting with http:// or https://. Files on the TV can only be added on the TV.',
    xtream: 'Enter only the server address, such as http://example.com:8080, without player_api.php or anything after a question mark.',
    stalker: 'Enter the portal address, such as http://example.com/c/.'
  };

  function refused(error, errorId) {
    if (error === 'unchanged') setText(errorId, 'Nothing has changed. Change something first.');
    else if (error === 'busy') setText(errorId, 'The TV is still waiting for you to save or reject another change.');
    else if (error === 'cooldown') setText(errorId, 'Your last change was rejected on the TV. Wait a few seconds before sending another.');
    else if (error === 'missing') setText(errorId, 'That entry no longer exists on the TV.');
    else if (error === 'locked') setText(errorId, fieldErrors.id);
    else setText(errorId, 'The TV couldn\'t take that request. Nothing was saved.');
  }

  function submit(event) {
    event.preventDefault();
    var body = { kind: current.kind, label: el('label').value.trim(), address: el('address').value.trim() };
    if (current.entry) body.id = current.entry.id;
    if (current.kind === 'xtream') { body.username = el('username').value.trim(); body.password = el('password').value; }
    if (current.kind === 'stalker') body.mac = el('mac').value.trim();
    if (current.kind === 'guide' && !current.entry && !el('guideForField').hidden && el('guideFor').value) body.source = el('guideFor').value;
    el('label').removeAttribute('aria-invalid');
    el('address').removeAttribute('aria-invalid');
    if (!body.label) { setText('formError', fieldErrors.label); el('label').setAttribute('aria-invalid', 'true'); el('label').focus(); return; }
    if (!current.entry && !body.address) { setText('formError', addressErrors[current.kind]); el('address').setAttribute('aria-invalid', 'true'); el('address').focus(); return; }
    el('send').disabled = true;
    var guide = current.kind === 'guide';
    var sent = { kind: current.kind, label: body.label, edit: !!current.entry, id: current.entry ? current.entry.id : null,
      source: body.source || null, known: ids(guide ? 'guides' : 'sources') };
    api('POST', 'api/changes', body).then(function (result) {
      el('send').disabled = false;
      el('password').value = '';
      body = null;
      if (common(result, 'formError')) return;
      if (result.status === 202 && result.data.id) { waitFor(result.data.id, guide ? 'guide' : 'source', sent); return; }
      var error = result.data.error;
      if (error === 'invalid' && result.data.reason === 'server') setText('formError', current.kind === 'stalker'
        ? 'The portal address has changed, so enter the MAC address again.'
        : 'The server address has changed, so enter the username and password again.');
      else if (error === 'invalid') {
        var field = result.data.field;
        setText('formError', field === 'address' ? addressErrors[current.kind] : (fieldErrors[field] || fieldErrors.body));
        if (field === 'address' || field === 'label') el(field).setAttribute('aria-invalid', 'true');
      }
      else if (error === 'missing' && sent.source) setText('formError', 'That source no longer exists on the TV. Choose another one.');
      else refused(error, 'formError');
    }, function () { el('send').disabled = false; el('password').value = ''; lost(); });
  }

  function sendSettings(event) {
    event.preventDefault();
    if (!saved) return;
    var diff = settingsDiff();
    setText('settingsError', '');
    if (diff.count === 0) { refused('unchanged', 'settingsError'); return; }
    el('sendSettings').disabled = true;
    api('POST', 'api/settings', diff.values).then(function (result) {
      el('sendSettings').disabled = false;
      if (common(result, 'settingsError')) return;
      if (result.status === 202 && result.data.id) { waitFor(result.data.id, 'settings'); return; }
      var error = result.data.error;
      if (error === 'invalid') setText('settingsError', settingErrors[result.data.field] || fieldErrors.body);
      else refused(error, 'settingsError');
    }, function () { el('sendSettings').disabled = false; lost(); });
  }

  function progress(state, title, text) {
    el('progress').className = 'card status narrow ' + state;
    el('progressTitle').textContent = title;
    el('progressText').textContent = text;
    el('done').hidden = state === 'wait';
    show('progress');
    if (state !== 'wait') el('done').focus();
  }

  function savedEntry(sent) {
    just = sent;
    load().then(function (shown) {
      if (!shown || just !== sent) return;
      if (!sent.id) {
        var group = sent.kind === 'guide' ? 'guides' : 'sources';
        var added = ((listing && listing[group]) || []).filter(function (e) { return sent.known.indexOf(e.id) < 0; });
        var match = added.filter(function (e) { return e.label === sent.label; })[0] || (added.length === 1 ? added[0] : null);
        if (match) { sent.id = match.id; renderSaved(); }
      }
      el('savedTitle').focus();
    });
  }

  function stopPolling() { if (polling) { clearTimeout(polling); polling = null; } }

  function waitFor(id, what, sent) {
    var misses = 0;
    var savedText = { source: 'The TV is loading the channels now.', guide: 'The TV is loading the guide now.', settings: 'Your new settings are saved. Some apply the next time you open Live TV.',
      links: 'The guides and their order are saved.', channel: 'The channel uses the new guide from now on.', profile: 'This page now changes the profile you chose.' };
    progress('wait', 'Check your TV', what === 'settings'
      ? 'Your TV is showing the settings you changed. Choose Save on this TV or Reject.'
      : what === 'profile' ? 'Your TV is asking whether this page may change another profile. Choose Allow or Reject on the TV.'
      : 'Your TV is asking whether to save this. Choose Save on this TV or Reject.');
    function poll() {
      api('GET', 'api/changes/' + encodeURIComponent(id)).then(function (result) {
        var status = result.data.status;
        if ((result.status === 200 && status === 'pending') || result.status === 429 || result.status >= 500) { polling = setTimeout(poll, 1500); return; }
        polling = null;
        if (common(result, 'formError')) return;
        if (status === 'saved' && sent) { savedEntry(sent); return; }
        if (status === 'saved') progress('ok', 'Saved on the TV', savedText[what]);
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
      if (common(result, 'pairError')) return;
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
  el('settings').addEventListener('change', function () { setText('settingsError', ''); refreshSettings(); });
  el('settings').addEventListener('submit', sendSettings);
  el('undo').addEventListener('click', function () { if (saved) renderSettings(saved); });
  el('done').addEventListener('click', load);
  el('savedClose').addEventListener('click', function () { just = null; renderSaved(); });
  el('savedGuide').addEventListener('click', function () { var s = just && findEntry('sources', just.id); if (s) openEditor('guide', null, s); });
  el('savedGuides').addEventListener('click', function () {
    var s = just && findEntry('sources', just.kind === 'guide' ? just.source : just.id);
    if (s) openAssign(s);
  });
  el('savedMore').addEventListener('click', function () { openEditor(just && just.kind === 'guide' ? 'guide' : 'm3u', null); });
  el('switchProfile').addEventListener('click', sendProfile);
  el('sendLinks').addEventListener('click', sendLinks);
  el('linksUndo').addEventListener('click', function () { assign.order = assign.saved.slice(); renderAssign(); });
  el('assignBack').addEventListener('click', function () { assign = null; load(); });
  el('openRecordings').addEventListener('click', openRecordings);
  el('recRefresh').addEventListener('click', loadRecordings);
  el('recBack').addEventListener('click', load);
  el('channelQuery').addEventListener('input', function () { later('channels', searchChannels); });
  el('guideQuery').addEventListener('input', function () { later('guide', searchGuide); });
  el('guideFeed').addEventListener('change', searchGuide);
  el('automatic').addEventListener('click', function () { if (assign && assign.channel) sendChannelGuide(null); });
  load();
})();
</script>
</body>
</html>
"""

    private const val ENDED = HEAD + """<body>
<main>
<header class="top">""" + MARK + """<div class="brand"><b>Nuvio</b><h1>Live TV setup</h1></div></header>
<section class="card status narrow no">
""" + ORB + """
<p class="big">This setup link has ended</p>
<p class="hint">Open Live TV setup on your TV and scan the QR code it shows. Each link works only while the setup screen is open.</p>
</section>
</main>
</body>
</html>
"""
}
