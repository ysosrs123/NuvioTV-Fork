package com.nuvio.tv.core.backup

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateFormat
import com.nuvio.tv.R
import java.util.Date
import java.util.Locale

object BackupWebPage {

    fun html(baseContext: Context, saving: Boolean, key: String, backups: List<SavedBackup>): String {
        val tag = baseContext.getSharedPreferences("app_locale", Context.MODE_PRIVATE).getString("locale_tag", null)
        val context = if (!tag.isNullOrEmpty()) {
            val config = Configuration(baseContext.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag))
            baseContext.createConfigurationContext(config)
        } else baseContext
        val rtl = context.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        fun s(id: Int) = escape(context.getString(id))
        val dateFormat = DateFormat.getMediumDateFormat(context)
        val timeFormat = DateFormat.getTimeFormat(context)
        val list = backups.joinToString("") { backup ->
            val date = Date(backup.modifiedMs)
            """<li class="item"><div><div class="name">${escape(dateFormat.format(date))}, ${escape(timeFormat.format(date))}</div>
<div class="sub">${escape(backup.displayName)}</div></div>
<a class="btn" href="/backup/${escape(backup.id)}?k=$key" download>${s(R.string.backup_web_download)}</a></li>"""
        }
        val body = if (saving) {
            """<div class="section-label">${s(R.string.backup_web_save_heading)}</div>
<p class="lead">${s(R.string.backup_web_save_body)}</p>
${if (backups.isEmpty()) """<p class="note">${s(R.string.backup_web_none)}</p>""" else """<ul class="list">$list</ul>"""}"""
        } else {
            """<div class="section-label">${s(R.string.backup_web_restore_heading)}</div>
<p class="lead">${s(R.string.backup_web_restore_body)}</p>
<input id="file" type="file" accept=".json,application/json" hidden>
<button class="btn btn-wide" id="choose">${s(R.string.backup_web_choose)}</button>
<p class="note" id="status" role="status"></p>"""
        }
        return """<!DOCTYPE html>
<html lang="${escape(context.resources.configuration.locales[0].toLanguageTag())}"${if (rtl) " dir=\"rtl\"" else ""}>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="referrer" content="no-referrer">
<title>${s(R.string.app_name)} - ${s(R.string.backup_title)}</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600&display=swap" rel="stylesheet">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body { font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #000; color: #fff; min-height: 100vh; line-height: 1.5; }
  .page { max-width: 600px; margin: 0 auto; padding: 0 1.5rem 6rem; }
  .header { text-align: center; padding: 3rem 0 2.5rem; border-bottom: 1px solid rgba(255,255,255,.05); margin-bottom: 2.5rem; }
  .header-logo { height: 48px; width: auto; margin-bottom: .5rem; }
  .header p { font-size: .875rem; font-weight: 300; color: rgba(255,255,255,.4); }
  .section-label { font-size: .75rem; font-weight: 500; color: rgba(255,255,255,.3); letter-spacing: .1em; text-transform: uppercase; margin-bottom: 1rem; }
  .lead { color: rgba(255,255,255,.7); font-size: .95rem; margin-bottom: 1.5rem; }
  .note { color: rgba(255,255,255,.5); font-size: .875rem; margin-top: 1.25rem; min-height: 1.3em; }
  .note.bad { color: #CF6679; }
  .list { list-style: none; display: flex; flex-direction: column; gap: .75rem; }
  .item { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 1rem 1.25rem; border: 1px solid rgba(255,255,255,.08); border-radius: 16px; }
  .name { font-weight: 500; }
  .sub { font-size: .75rem; color: rgba(255,255,255,.4); word-break: break-all; }
  .btn { display: inline-flex; align-items: center; justify-content: center; background: transparent; border: 1px solid rgba(255,255,255,.2); border-radius: 100px; padding: .875rem 1.5rem; color: #fff; font-family: inherit; font-size: .875rem; font-weight: 500; cursor: pointer; text-decoration: none; white-space: nowrap; transition: all .3s ease; }
  .btn:hover { background: #fff; color: #000; border-color: #fff; }
  .btn:active { transform: scale(.97); }
  .btn-wide { width: 100%; padding: 1rem; font-size: .95rem; font-weight: 600; }
  .btn:disabled { opacity: .3; pointer-events: none; }
  @media (max-width: 480px) { .header-logo { height: 40px; } .item { flex-direction: column; align-items: stretch; } }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <img src="/logo.png" alt="${s(R.string.app_name)}" class="header-logo">
    <p>${s(R.string.backup_title)}</p>
  </div>
  $body
</div>
${if (saving) "" else """<script>
  const file = document.getElementById('file');
  const choose = document.getElementById('choose');
  const status = document.getElementById('status');
  choose.addEventListener('click', () => file.click());
  file.addEventListener('change', async () => {
    const f = file.files[0];
    if (!f) return;
    choose.disabled = true;
    status.className = 'note';
    try {
      const text = await f.text();
      const res = await fetch('/restore?k=$key', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: text });
      if (res.ok) { status.textContent = ${jsString(context.getString(R.string.backup_web_sent))}; }
      else { status.className = 'note bad'; status.textContent = res.status === 400 ? ${jsString(context.getString(R.string.backup_web_not_backup))} : ${jsString(context.getString(R.string.backup_web_failed))}; choose.disabled = false; }
    } catch (e) {
      status.className = 'note bad'; status.textContent = ${jsString(context.getString(R.string.backup_web_failed))}; choose.disabled = false;
    }
    file.value = '';
  });
</script>"""}
</body>
</html>"""
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun jsString(text: String): String =
        "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("<", "\\x3c") + "'"
}
