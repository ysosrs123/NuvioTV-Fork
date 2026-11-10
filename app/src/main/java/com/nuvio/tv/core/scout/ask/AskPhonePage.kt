package com.nuvio.tv.core.scout.ask

import android.content.Context
import android.content.res.Configuration
import com.nuvio.tv.R
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

private const val CHATGPT_HUNDRED_QUESTIONS = "US$0.05"
private const val CLAUDE_HUNDRED_QUESTIONS = "US$1"

internal object AskPhonePage {

    fun html(rawContext: Context, token: String, startOnKey: Boolean): String {
        val tag = rawContext.getSharedPreferences("app_locale", Context.MODE_PRIVATE).getString("locale_tag", null)
        val context = if (!tag.isNullOrEmpty()) {
            val config = Configuration(rawContext.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag))
            rawContext.createConfigurationContext(config)
        } else rawContext
        val rtl = context.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL

        val texts = buildJsonObject {
            put("sent", context.getString(R.string.scout_web_question_sent))
            put("keyPlaceholder", context.getString(R.string.scout_web_key_placeholder))
            put("keySaved", context.getString(R.string.scout_web_key_saved_placeholder))
            put("testing", context.getString(R.string.scout_web_testing))
            put("invalid", context.getString(R.string.scout_web_error_invalid))
            put("lost", context.getString(R.string.scout_web_error_connection))
        }
        val providers = AskProvider.entries.map { provider ->
            buildJsonObject {
                put("id", provider.id)
                put("name", providerName(context, provider))
                put("steps", providerSteps(context, provider))
            }
        }
        val data = buildJsonObject {
            put("token", token)
            put("startOnKey", startOnKey)
            put("texts", texts)
            put("providers", kotlinx.serialization.json.JsonArray(providers))
        }.toString().replace("<", "\\u003c")

        fun s(id: Int) = escapeHtml(context.getString(id))

        return """
<!DOCTYPE html>
<html lang="${escapeHtml(context.resources.configuration.locales[0].language)}" dir="${if (rtl) "rtl" else "ltr"}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${s(R.string.scout_web_title)}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
body{font-family:'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#000;color:#fff;display:flex;justify-content:center;min-height:100vh;padding:16px}
.card{max-width:560px;width:100%;padding:16px 0}
h1{font-size:20px;font-weight:600;margin:0 0 12px}
section{margin-bottom:32px}
label{display:block;font-size:13px;font-weight:500;color:rgba(255,255,255,.6);margin:14px 0 6px}
textarea,input,select{width:100%;padding:12px 18px;border-radius:100px;border:1px solid rgba(255,255,255,.2);background:transparent;color:#fff;font:inherit;font-size:16px}
select option{color:#000}
textarea{min-height:96px;resize:vertical;border-radius:8px;padding:12px 14px}
button{width:100%;margin-top:14px;padding:14px;border-radius:100px;border:0;background:#fff;color:#000;font:inherit;font-size:16px;font-weight:600}
.steps{white-space:pre-line;font-size:14px;line-height:1.5;color:rgba(255,255,255,.75);margin-top:12px}
.note{font-size:13px;color:rgba(255,255,255,.5);margin-top:12px}
.msg{font-size:14px;margin-top:12px;min-height:20px}
.bad{color:#CF6679}.good{color:#a5d6a7}
details{margin-top:14px}summary{font-size:14px;color:rgba(255,255,255,.75)}
.hidden{display:none}
.header{text-align:center;padding:16px 0 24px;border-bottom:1px solid rgba(255,255,255,.05);margin-bottom:24px}
.header-logo{height:40px;width:auto}
</style>
</head>
<body><div class="card"><div class="header"><img src="/logo.png?t=${escapeHtml(token)}" alt="${s(R.string.app_name)}" class="header-logo"></div><div id="root">
<section id="ask">
<h1>${s(R.string.scout_web_question_title)}</h1>
<textarea id="question" maxlength="$ASK_MAX_QUESTION" placeholder="${s(R.string.scout_web_question_placeholder)}"></textarea>
<button id="send">${s(R.string.scout_web_question_send)}</button>
<div class="msg" id="askMsg"></div>
</section>
<section id="key">
<h1>${s(R.string.scout_web_key_title)}</h1>
<label for="provider">${s(R.string.scout_web_service)}</label>
<select id="provider"></select>
<div class="steps" id="steps"></div>
<label for="apikey">${s(R.string.scout_web_key_label)}</label>
<input id="apikey" type="password" autocomplete="off" autocapitalize="off" spellcheck="false">
<details id="adv"><summary>${s(R.string.scout_web_advanced)}</summary>
<label for="model">${s(R.string.scout_web_model)}</label>
<input id="model" autocomplete="off" autocapitalize="off" spellcheck="false">
<div id="addressBox"><label for="base">${s(R.string.scout_web_address)}</label>
<input id="base" type="url" autocomplete="off" autocapitalize="off" spellcheck="false">
<div class="note">${s(R.string.scout_web_address_hint)}</div></div>
</details>
<button id="save">${s(R.string.scout_web_save)}</button>
<div class="msg" id="keyMsg"></div>
<div class="note">${s(R.string.scout_web_key_note)}</div>
</section>
</div></div>
<script>
const D=$data;const T=D.texts;const ${'$'}=i=>document.getElementById(i);
const H={'x-ask-token':D.token,'Content-Type':'application/json'};
let chosen=false;
D.providers.forEach(p=>{const o=document.createElement('option');o.value=p.id;o.textContent=p.name;${'$'}('provider').appendChild(o)});
if(D.startOnKey){${'$'}('root').insertBefore(${'$'}('key'),${'$'}('ask'))}
let status=[];
function show(){const id=${'$'}('provider').value;const p=D.providers.find(x=>x.id===id);${'$'}('steps').textContent=p.steps;
const other=id==='other';${'$'}('adv').className=other?'':'hidden';${'$'}('adv').open=other;
const st=status.find(x=>x.id===id);${'$'}('apikey').placeholder=st&&st.hasKey?T.keySaved:T.keyPlaceholder;${'$'}('model').value=st?st.model:''}
${'$'}('provider').addEventListener('change',()=>{chosen=true;show()});
function msg(id,text,cls){const e=${'$'}(id);e.textContent=text;e.className='msg '+(cls||'')}
async function poll(){try{const r=await fetch('/api/state',{headers:H});if(!r.ok)throw 0;const s=await r.json();status=s.providers;
${'$'}('ask').className=s.active?'':'hidden';if(!chosen&&${'$'}('provider').value!==s.selected){${'$'}('provider').value=s.selected;show()}
if(s.test)msg('keyMsg',s.test,s.testGood?'good':'bad')}catch(e){msg('keyMsg',T.lost,'bad')}}
${'$'}('save').addEventListener('click',async()=>{const key=${'$'}('apikey').value.trim();if(!key){msg('keyMsg',T.invalid,'bad');return}
try{const r=await fetch('/api/key',{method:'POST',headers:H,body:JSON.stringify({provider:${'$'}('provider').value,key:key,model:${'$'}('model').value.trim(),baseUrl:${'$'}('base').value.trim()})});
if(r.ok){${'$'}('apikey').value='';msg('keyMsg',T.testing)}else msg('keyMsg',T.invalid,'bad')}catch(e){msg('keyMsg',T.lost,'bad')}});
${'$'}('send').addEventListener('click',async()=>{const q=${'$'}('question').value.trim();if(!q)return;
try{const r=await fetch('/api/question',{method:'POST',headers:H,body:JSON.stringify({question:q})});
if(r.ok){${'$'}('question').value='';msg('askMsg',T.sent,'good')}else msg('askMsg',T.invalid,'bad')}catch(e){msg('askMsg',T.lost,'bad')}});
poll().then(show);setInterval(poll,2000);
</script>
</body></html>
""".trimIndent()
    }

    private fun providerName(context: Context, provider: AskProvider): String = when (provider) {
        AskProvider.GEMINI -> "Gemini"
        AskProvider.GROQ -> "Groq"
        AskProvider.CHATGPT -> "OpenAI"
        AskProvider.CLAUDE -> "Claude"
        AskProvider.OTHER -> context.getString(R.string.scout_ai_other)
    }

    private fun providerSteps(context: Context, provider: AskProvider): String = when (provider) {
        AskProvider.GEMINI -> context.getString(R.string.scout_web_steps_gemini)
        AskProvider.GROQ -> context.getString(R.string.scout_web_steps_groq)
        AskProvider.CHATGPT -> context.getString(R.string.scout_web_steps_chatgpt, CHATGPT_HUNDRED_QUESTIONS)
        AskProvider.CLAUDE -> context.getString(R.string.scout_web_steps_claude, CLAUDE_HUNDRED_QUESTIONS)
        AskProvider.OTHER -> context.getString(R.string.scout_web_steps_other)
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
