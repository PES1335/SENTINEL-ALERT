// Sentinel — QG : l'IA tactique (Cloudflare Workers AI via ton serveur)
(() => {
const $ = s => document.querySelector(s);
const hist = [];
let busy = false;
function add(cls, text, ops){
  const d = document.createElement("div"); d.className = "msg " + cls; d.textContent = text;
  if(ops){ const o = document.createElement("span"); o.className = "ops"; o.textContent = ops; d.append(o) }
  $("#qgLog").append(d); d.scrollIntoView({block:"end", behavior:"smooth"}); return d;
}
function lock(on){ busy = on; ["#qgSend","#qgInput"].forEach(q => $(q).disabled = on); document.querySelectorAll(".qg-quick button").forEach(b => b.disabled = on) }
async function ask(text){
  text = (text||"").trim(); if(!text || busy || !SA.ready) return;
  lock(true); add("me", text); const wait = add("ai", "Transmission…");
  try{
    const r = await SA.call("/ai", { message: text, history: hist });
    wait.remove();
    const o = r.ops || {}; const parts = [];
    if(o.ajoutes) parts.push(o.ajoutes + " objectif" + (o.ajoutes>1?"s":"") + " ajouté" + (o.ajoutes>1?"s":""));
    if(o.modifies) parts.push(o.modifies + " modifié" + (o.modifies>1?"s":""));
    if(o.supprimes) parts.push(o.supprimes + " supprimé" + (o.supprimes>1?"s":""));
    if(o.confirmes) parts.push(o.confirmes + " agent" + (o.confirmes>1?"s":"") + " confirmé" + (o.confirmes>1?"s":""));
    add("ai", r.reply || "Reçu.", parts.join(" · "));
    hist.push({role:"user", content:text}, {role:"assistant", content:r.reply||""});
    if(parts.length){ SA.native("reschedule"); if(window.SATasks) window.SATasks.refresh(); document.dispatchEvent(new Event("visibilitychange")); }
  }catch(e){
    wait.remove();
    add("err", /branchée/.test(e.message) ? e.message : "Le QG ne répond pas (" + e.message + "). Réessaie dans un instant.");
  }finally{ lock(false) }
}
$("#qgForm").addEventListener("submit", e => { e.preventDefault(); const v = $("#qgInput").value; $("#qgInput").value = ""; ask(v) });
document.querySelectorAll(".qg-quick button").forEach(b => b.onclick = () => ask(b.dataset.q));
})();
