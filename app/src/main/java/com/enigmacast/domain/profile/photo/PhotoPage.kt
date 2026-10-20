package com.enigmacast.domain.profile.photo

/** The phone page: language from Accept-Language, plain HTML/JS, nothing loaded from elsewhere. */
object PhotoPage {
    val LANGUAGES = listOf("en", "nl", "de", "it", "fr")

    /** First supported language in Accept-Language order (q values respected); English otherwise. */
    fun language(acceptLanguage: String?): String {
        if (acceptLanguage.isNullOrBlank()) return "en"
        return acceptLanguage.split(',').mapIndexedNotNull { i, part ->
            val pieces = part.trim().split(';')
            val tag = pieces[0].trim().lowercase().substringBefore('-')
            val q = pieces.drop(1).map { it.trim() }.firstOrNull { it.startsWith("q=") }?.removePrefix("q=")?.toDoubleOrNull() ?: 1.0
            if (tag in LANGUAGES && q > 0) Triple(tag, q, i) else null
        }.sortedWith(compareBy({ -it.second }, { it.third })).firstOrNull()?.first ?: "en"
    }

    private val texts: Map<String, Map<String, String>> = mapOf(
        "en" to mapOf(
            "title" to "Set profile photo", "for" to "For profile", "pick" to "Choose photo from gallery",
            "hint" to "Drag to move, pinch or use the slider to zoom. The square becomes the photo.",
            "zoom" to "Zoom", "save" to "Save", "saving" to "Sending to the TV…",
            "done" to "Done! The photo is now on your TV.", "doneSub" to "You can close this page.",
            "err" to "That did not work.", "errToken" to "This link has expired or was already used. Scan the new QR code on the TV.",
            "errSize" to "This photo is too large (max 10 MB).", "errImage" to "This file is not a photo the TV can read.",
            "errNet" to "The TV could not be reached. Is your phone on the same network?",
        ),
        "nl" to mapOf(
            "title" to "Profielfoto instellen", "for" to "Voor profiel", "pick" to "Kies foto uit galerij",
            "hint" to "Sleep om te verplaatsen, knijp of gebruik de schuif om te zoomen. Het vierkant wordt de foto.",
            "zoom" to "Zoom", "save" to "Opslaan", "saving" to "Bezig met versturen naar de tv…",
            "done" to "Klaar! De foto staat nu op je tv.", "doneSub" to "Je kunt deze pagina sluiten.",
            "err" to "Dat lukte niet.", "errToken" to "Deze link is verlopen of al gebruikt. Scan de nieuwe QR-code op de tv.",
            "errSize" to "Deze foto is te groot (max. 10 MB).", "errImage" to "Dit bestand is geen foto die de tv kan lezen.",
            "errNet" to "De tv is niet bereikbaar. Zit je telefoon op hetzelfde netwerk?",
        ),
        "de" to mapOf(
            "title" to "Profilfoto festlegen", "for" to "Für Profil", "pick" to "Foto aus Galerie wählen",
            "hint" to "Zum Verschieben ziehen, zum Zoomen zwei Finger oder den Regler nutzen. Das Quadrat wird das Foto.",
            "zoom" to "Zoom", "save" to "Speichern", "saving" to "Wird an den Fernseher gesendet…",
            "done" to "Fertig! Das Foto ist jetzt auf deinem Fernseher.", "doneSub" to "Du kannst diese Seite schließen.",
            "err" to "Das hat nicht geklappt.", "errToken" to "Dieser Link ist abgelaufen oder wurde schon benutzt. Scanne den neuen QR-Code am Fernseher.",
            "errSize" to "Dieses Foto ist zu groß (max. 10 MB).", "errImage" to "Diese Datei ist kein Foto, das der Fernseher lesen kann.",
            "errNet" to "Der Fernseher ist nicht erreichbar. Ist dein Handy im selben Netzwerk?",
        ),
        "it" to mapOf(
            "title" to "Imposta foto profilo", "for" to "Per il profilo", "pick" to "Scegli foto dalla galleria",
            "hint" to "Trascina per spostare, pizzica o usa il cursore per lo zoom. Il quadrato diventa la foto.",
            "zoom" to "Zoom", "save" to "Salva", "saving" to "Invio alla TV…",
            "done" to "Fatto! La foto ora è sulla tua TV.", "doneSub" to "Puoi chiudere questa pagina.",
            "err" to "Non ha funzionato.", "errToken" to "Questo link è scaduto o è già stato usato. Scansiona il nuovo codice QR sulla TV.",
            "errSize" to "Questa foto è troppo grande (max 10 MB).", "errImage" to "Questo file non è una foto che la TV può leggere.",
            "errNet" to "La TV non è raggiungibile. Il telefono è sulla stessa rete?",
        ),
        "fr" to mapOf(
            "title" to "Définir la photo de profil", "for" to "Pour le profil", "pick" to "Choisir une photo dans la galerie",
            "hint" to "Faites glisser pour déplacer, pincez ou utilisez le curseur pour zoomer. Le carré devient la photo.",
            "zoom" to "Zoom", "save" to "Enregistrer", "saving" to "Envoi vers la TV…",
            "done" to "C’est fait ! La photo est maintenant sur votre TV.", "doneSub" to "Vous pouvez fermer cette page.",
            "err" to "Cela n’a pas fonctionné.", "errToken" to "Ce lien a expiré ou a déjà été utilisé. Scannez le nouveau code QR sur la TV.",
            "errSize" to "Cette photo est trop grande (10 Mo max.).", "errImage" to "Ce fichier n’est pas une photo lisible par la TV.",
            "errNet" to "La TV est injoignable. Votre téléphone est-il sur le même réseau ?",
        ),
    )

    fun text(lang: String, key: String): String = texts[lang]?.get(key) ?: texts.getValue("en").getValue(key)

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c").replace("\n", "\\n") + "\""

    /** Small error page (wrong or spent token). */
    fun gone(lang: String): String = shell(lang, "<p class=msg>${escape(text(lang, "errToken"))}</p>", "")

    /** The upload page. [profileName] is shown, never the token (the script reads it from the URL). */
    fun page(lang: String, profileName: String): String {
        val t = { k: String -> escape(text(lang, k)) }
        val body = """
<p class=sub>${t("for")}: <b>${escape(profileName)}</b></p>
<label class=btn id=pickLabel><input type=file id=file accept="image/*" hidden>${t("pick")}</label>
<div id=editor hidden>
<div id=stage><canvas id=cv></canvas><div id=frame></div></div>
<p class=hint>${t("hint")}</p>
<label class=zoom>${t("zoom")} <input type=range id=zoom min=1 max=4 step=0.01 value=1></label>
<button class="btn primary" id=save>${t("save")}</button>
</div>
<p class=msg id=msg></p>
<div id=done hidden><div class=check>✓</div><p class=big>${t("done")}</p><p class=sub>${t("doneSub")}</p></div>
"""
        val i18n = listOf("saving", "err", "errToken", "errSize", "errImage", "errNet").joinToString(",") { "${it}:${js(text(lang, it))}" }
        return shell(lang, body, SCRIPT.replace("/*I18N*/", i18n))
    }

    private fun shell(lang: String, body: String, script: String) = """<!doctype html>
<html lang="$lang"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta name="referrer" content="no-referrer">
<title>EnigmaCast · ${escape(text(lang, "title"))}</title>
<style>$STYLE</style></head><body>
<header><div class=logo>E</div><div><div class=brand>EnigmaCast</div><h1>${escape(text(lang, "title"))}</h1></div></header>
<main>$body</main>
${if (script.isEmpty()) "" else "<script>$script</script>"}
</body></html>"""

    private const val STYLE = """
*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#0b1020;color:#e8ecf6}
header{display:flex;gap:12px;align-items:center;padding:18px 20px;background:linear-gradient(135deg,#13204a,#0b1020)}
.logo{width:44px;height:44px;border-radius:12px;background:linear-gradient(135deg,#2f7bff,#22d3ee);display:flex;align-items:center;justify-content:center;font-weight:800;font-size:24px;color:#fff}
.brand{font-size:13px;letter-spacing:.08em;text-transform:uppercase;color:#8fb4ff}h1{margin:2px 0 0;font-size:21px}
main{padding:18px 20px 40px;max-width:520px;margin:0 auto}.sub{color:#a9b3c9}.hint{color:#a9b3c9;font-size:14px}
.btn{display:block;width:100%;padding:15px;margin:14px 0;border:1px solid #2f7bff;border-radius:12px;background:#15203d;color:#fff;font-size:17px;text-align:center;cursor:pointer}
.btn.primary{background:#2f7bff;border-color:#2f7bff;font-weight:700}.btn:disabled{opacity:.5}
#stage{position:relative;width:100%;aspect-ratio:1;background:#000;border-radius:12px;overflow:hidden;touch-action:none}
#cv{width:100%;height:100%;display:block}#frame{position:absolute;inset:8%;border:3px solid #22d3ee;border-radius:10px;box-shadow:0 0 0 999px rgba(0,0,0,.55);pointer-events:none}
.zoom{display:flex;gap:10px;align-items:center;color:#a9b3c9}.zoom input{flex:1}
.msg{color:#ffb4b4;min-height:1em}#done{text-align:center;padding-top:30px}.check{width:84px;height:84px;margin:0 auto;border-radius:50%;background:#16a34a;color:#fff;font-size:52px;line-height:84px}.big{font-size:20px;font-weight:700}
"""

    // Crop in the browser's view of the photo (EXIF rotation applied); the TV gets the original
    // file plus the square as fractions. Large files are re-encoded on the phone first.
    private const val SCRIPT = """
(function(){
var T={/*I18N*/};var MAX=10*1024*1024;
var file=document.getElementById('file'),cv=document.getElementById('cv'),ctx=cv.getContext('2d'),zoomEl=document.getElementById('zoom'),
save=document.getElementById('save'),msg=document.getElementById('msg'),stage=document.getElementById('stage');
var img=null,blob=null,scale=1,base=1,ox=0,oy=0,ptrs={},pinch=null;
var token=new URLSearchParams(location.search).get('token')||'';
function size(){var r=stage.getBoundingClientRect();var d=window.devicePixelRatio||1;cv.width=Math.round(r.width*d);cv.height=cv.width;}
function frame(){var w=cv.width;return{x:w*0.08,s:w*0.84};}
function clamp(){var f=frame(),k=base*scale,w=img.width*k,h=img.height*k;ox=Math.min(f.x,Math.max(f.x+f.s-w,ox));oy=Math.min(f.x,Math.max(f.x+f.s-h,oy));}
function draw(){if(!img)return;clamp();ctx.fillStyle='#000';ctx.fillRect(0,0,cv.width,cv.height);var k=base*scale;ctx.drawImage(img,ox,oy,img.width*k,img.height*k);}
function setZoom(z,cx,cy){var k0=base*scale;scale=Math.max(1,Math.min(4,z));var k1=base*scale;if(cx===undefined){cx=cy=cv.width/2;}
ox=cx-(cx-ox)*k1/k0;oy=cy-(cy-oy)*k1/k0;zoomEl.value=scale;draw();}
function load(f){msg.textContent='';var url=URL.createObjectURL(f);var im=new Image();im.onload=function(){img=im;
document.getElementById('editor').hidden=false;size();var f0=frame();base=f0.s/Math.min(im.width,im.height);scale=1;
ox=f0.x+(f0.s-im.width*base)/2;oy=f0.x+(f0.s-im.height*base)/2;zoomEl.value=1;draw();
if(f.size>MAX){var c=document.createElement('canvas');var m=Math.min(1,4096/Math.max(im.width,im.height));c.width=Math.round(im.width*m);c.height=Math.round(im.height*m);
c.getContext('2d').drawImage(im,0,0,c.width,c.height);c.toBlob(function(b){blob=b;},'image/jpeg',0.92);}else{blob=f;}};
im.onerror=function(){msg.textContent=T.errImage;};im.src=url;}
file.onchange=function(){if(file.files&&file.files[0])load(file.files[0]);};
zoomEl.oninput=function(){setZoom(parseFloat(zoomEl.value));};
function pos(e){var r=cv.getBoundingClientRect(),d=cv.width/r.width;return{x:(e.clientX-r.left)*d,y:(e.clientY-r.top)*d};}
cv.addEventListener('pointerdown',function(e){cv.setPointerCapture(e.pointerId);ptrs[e.pointerId]=pos(e);
var ids=Object.keys(ptrs);if(ids.length==2){var a=ptrs[ids[0]],b=ptrs[ids[1]];pinch={d:Math.hypot(a.x-b.x,a.y-b.y),z:scale};}});
cv.addEventListener('pointermove',function(e){if(!ptrs[e.pointerId]||!img)return;var p=pos(e),ids=Object.keys(ptrs);
if(ids.length==2&&pinch){ptrs[e.pointerId]=p;var a=ptrs[ids[0]],b=ptrs[ids[1]];setZoom(pinch.z*Math.hypot(a.x-b.x,a.y-b.y)/pinch.d,(a.x+b.x)/2,(a.y+b.y)/2);return;}
var o=ptrs[e.pointerId];ox+=p.x-o.x;oy+=p.y-o.y;ptrs[e.pointerId]=p;draw();});
function up(e){delete ptrs[e.pointerId];if(Object.keys(ptrs).length<2)pinch=null;}
cv.addEventListener('pointerup',up);cv.addEventListener('pointercancel',up);
cv.addEventListener('wheel',function(e){e.preventDefault();var p=pos(e);setZoom(scale*(e.deltaY<0?1.1:0.9),p.x,p.y);},{passive:false});
save.onclick=function(){if(!img||!blob)return;var f=frame(),k=base*scale;
var x=(f.x-ox)/k/img.width,y=(f.x-oy)/k/img.height,s=f.s/k/img.width;
save.disabled=true;msg.textContent=T.saving;
fetch('/upload?token='+encodeURIComponent(token)+'&x='+x.toFixed(6)+'&y='+y.toFixed(6)+'&s='+s.toFixed(6),
{method:'POST',headers:{'Content-Type':blob.type||'application/octet-stream'},body:blob})
.then(function(r){return r.json().catch(function(){return{};}).then(function(j){return{ok:r.ok,code:j.error};});})
.then(function(r){if(r.ok){document.getElementById('editor').hidden=true;document.getElementById('pickLabel').hidden=true;msg.textContent='';document.getElementById('done').hidden=false;}
else{save.disabled=false;msg.textContent=r.code=='token'?T.errToken:r.code=='size'?T.errSize:r.code=='image'?T.errImage:T.err;}})
.catch(function(){save.disabled=false;msg.textContent=T.errNet;});};
window.addEventListener('resize',function(){if(img){size();var f0=frame();base=f0.s/Math.min(img.width,img.height);draw();}});
})();
"""
}
