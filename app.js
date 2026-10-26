// Sentinel Alertes — appli
(() => {
const $ = s => document.querySelector(s);
const CFG = "sentinel.cfg";
let cfg = null, state = null;
/* Dans l'appli Android, le moteur d'alarmes natif est accessible ici */
const NATIVE = window.SentinelNative || null;
function nativeCall(fn, ...a){ try{ return NATIVE && NATIVE[fn] ? NATIVE[fn](...a) : null }catch(e){ return null } }

function load(){ try{ return JSON.parse(localStorage.getItem(CFG)||"null") }catch(e){ return null } }
async function save(c){
  cfg = c; try{ localStorage.setItem(CFG, JSON.stringify(c)) }catch(e){}
  nativeCall("setConfig", c.url, c.key);
  try{ const cache = await caches.open("sentinel-cfg"); await cache.put("/cfg", new Response(JSON.stringify(c), {headers:{"content-type":"application/json"}})) }catch(e){}
}
let toastT;
function toast(m){ const t=$("#toast"); t.textContent=m; t.hidden=false; clearTimeout(toastT); toastT=setTimeout(()=>t.hidden=true,2600) }
const esc = s => String(s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const fmtPhone = t => (t||"").replace(/(\d{2})(?=\d)/g,"$1 ");

async function call(path, body){
  const r = await fetch(cfg.url + path, { method: body ? "POST" : "GET", headers: { "x-key": cfg.key, "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined, cache: "no-store" });
  const j = await r.json().catch(()=>({}));
  if(!r.ok) throw new Error(j.error || ("Erreur " + r.status));
  return j;
}

/* ---------- Liaison ---------- */
$("#link").onclick = async () => {
  let url = $("#srv").value.trim().replace(/\/+$/,"");
  if(!/^https:\/\//.test(url)) { toast("L'adresse doit commencer par https://"); return }
  const code = $("#code").value.trim();
  try{
    if(code){ await save({url, key: code}); await call("/today"); }
    else {
      const r = await fetch(url + "/setup", { method: "POST" });
      const j = await r.json().catch(()=>({}));
      if(r.status === 409){ $("#codeField").hidden = false; toast("Serveur déjà relié : entre ton code d'accès"); return }
      if(!r.ok || !j.key) throw new Error(j.error || "Serveur injoignable");
      await save({url, key: j.key});
    }
    toast("Serveur relié"); boot();
  }catch(e){ toast(e.message || "Liaison impossible"); }
};

/* ---------- Notifications ---------- */
function b64ToBytes(s){ s=s.replace(/-/g,"+").replace(/_/g,"/"); while(s.length%4) s+="="; const b=atob(s); return Uint8Array.from(b,c=>c.charCodeAt(0)) }
async function swReg(){ return "serviceWorker" in navigator ? navigator.serviceWorker.ready : null }
const PERMS = { notif:"Notifications", fsi:"Alarme plein écran", exact:"Alarmes à l'heure exacte", battery:"Batterie sans restriction" };
function refreshNative(){
  const p = $("#notif"); p.hidden = view === "qg";
  let st = {}; try{ st = JSON.parse(nativeCall("status") || "{}") }catch(e){}
  const missing = Object.keys(PERMS).filter(k => st[k] === false);
  const ok = !missing.length;
  p.classList.toggle("alert", !ok);
  $("#notifTitle").textContent = ok ? "Alarmes plein écran actives" : "Autorisations à donner";
  const next = st.next ? `Prochaine alarme : <b>${esc(st.nextText||"")}</b>` : "Aucune alarme prévue pour l'instant.";
  $("#notifText").innerHTML = ok ? next :
    `Touche chaque bouton pour autoriser Sentinel :<div class="row">${missing.map(k=>`<button class="btn ghost" data-perm="${k}">✗ ${PERMS[k]}</button>`).join("")}</div>`;
  $("#enable").hidden = true; $("#test").hidden = false; $("#test").textContent = "Tester l'alarme";
  let snd = $("#sound");
  if(!snd){ snd = document.createElement("button"); snd.id = "sound"; snd.className = "btn ghost"; $("#test").after(snd); snd.onclick = () => nativeCall("pickSound"); }
  snd.textContent = "🔔 Sonnerie : " + (nativeCall("soundTitle") || "par défaut");
}
$("#notifText").addEventListener("click", e => { const b = e.target.closest("[data-perm]"); if(b) nativeCall("fix", b.dataset.perm) });
async function refreshNotif(){
  if(NATIVE) return refreshNative();
  const p = $("#notif");
  p.hidden = view === "qg";
  if(!("Notification" in window) || !("PushManager" in window)){
    $("#notifTitle").textContent = "Notifications indisponibles";
    $("#notifText").textContent = "Ouvre Sentinel depuis Chrome puis installe-la sur l'écran d'accueil (menu ⋮ > Installer l'application).";
    $("#enable").hidden = true; return;
  }
  const reg = await swReg(); const sub = reg && await reg.pushManager.getSubscription();
  const on = Notification.permission === "granted" && !!sub;
  p.classList.toggle("alert", !on);
  $("#notifTitle").textContent = on ? "Alertes actives" : (Notification.permission === "denied" ? "Notifications bloquées" : "Alertes désactivées");
  $("#notifText").textContent = on ? "Sentinel sonne à chaque début de service non confirmé, même appli fermée." :
    Notification.permission === "denied" ? "Autorise les notifications pour Sentinel dans les réglages Android (Applis > Sentinel > Notifications), puis reviens ici." :
    "Active-les pour que Sentinel sonne même quand l'appli est fermée.";
  $("#enable").hidden = on; $("#test").hidden = !on;
}
$("#enable").onclick = async () => {
  try{
    const perm = await Notification.requestPermission();
    if(perm !== "granted"){ refreshNotif(); return }
    const reg = await swReg();
    const { vapid } = await call("/vapid");
    let sub = await reg.pushManager.getSubscription();
    if(!sub) sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(vapid) });
    await call("/subscribe", sub.toJSON());
    toast("Alertes activées"); refreshNotif();
  }catch(e){ toast("Activation impossible : " + (e.message||e)); }
};
$("#test").onclick = async () => {
  if(NATIVE){ nativeCall("test"); toast("Verrouille ton téléphone : l'alarme arrive dans 10 s"); return }
  try{ const r = await call("/test", {}); toast(r.sent ? "Test envoyé · ferme l'appli pour voir" : "Aucun appareil joignable, réactive les alertes"); }
  catch(e){ toast(e.message) }
};

/* ---------- Aujourd'hui ---------- */
const toMin = t => { const [h,m]=t.split(":").map(Number); return h*60+m };
function render(){
  if(!state) return;
  const { main, reps, now, settings } = state;
  const d = new Date(state.date + "T12:00");
  $("#date").textContent = d.toLocaleDateString("fr-FR",{weekday:"long",day:"numeric",month:"long"});
  const ok = main.filter(r=>r.done).length, n = main.length;
  $("#count").textContent = `${ok}/${n}`; $("#bar").style.width = n ? Math.round(ok/n*100)+"%" : "0";
  const nowMin = toMin(now), off = +settings.offset || 0;
  const slots = {}; main.forEach(r => (slots[r.start]=slots[r.start]||[]).push(r));
  let anyLate = false;
  const card = r => { const tel = r.tel || r.telSite;
    return `<div class="item${r.done?" done":""}" data-key="${esc(r.key)}">
      <button class="check" aria-label="${r.done?"Annuler":"Confirmer"} ${esc(r.agent||r.placeholder)}"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="3.5" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg></button>
      <div class="body"><div class="who">${esc(r.site.replace("New Yorker ","NY "))}</div>
        <div class="meta"><span class="t">${r.start} → ${r.end}</span><span>${esc(r.agent || ("Poste " + r.placeholder))}</span>${r.sub?'<span class="tag">Sous-traitant</span>':""}<span>${tel?fmtPhone(tel):"Pas de numéro"}</span></div></div>
      ${tel?`<a class="call" href="tel:${tel}" aria-label="Appeler"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"/></svg></a>`:"<span></span>"}
    </div>` };
  const html = Object.keys(slots).sort().map(t => {
    const list = slots[t], left = list.filter(r=>!r.done).length;
    const late = left && state.date === state.today && nowMin >= toMin(t) + off;
    if(late) anyLate = true;
    return `<section class="slot${late?" late":""}"><h2><span><b>${t}</b> · ${list.length} agent${list.length>1?"s":""}</span><span>${left?left+" à confirmer":"OK"}</span></h2><div class="list">${list.sort((a,b)=>!!a.done-!!b.done).map(card).join("")}</div></section>`;
  }).join("") + (reps.length ? `<details><summary>Reprises après la pause · ${reps.length} (non suivies)</summary><div class="list">${reps.map(card).join("")}</div></details>` : "");
  $("#slots").innerHTML = n ? html : `<p class="empty">Aucun début de service aujourd'hui. Importe un planning dans les réglages.</p>`;
  $("#count").classList.toggle("late", anyLate);
  badge("#badgeService", main.filter(r=>!r.done && state.date===state.today && nowMin >= toMin(r.start) + off).length);
  $("#offset").value = String(settings.offset||0); $("#repeat").value = String(settings.repeat||10);
  $("#range").textContent = state.range ? `Planning chargé du ${state.range[0].split("-").reverse().slice(0,2).join("/")} au ${state.range[1].split("-").reverse().slice(0,2).join("/")}.` : "Aucun planning chargé.";
}
async function refresh(){
  try{ state = await call("/today"); render(); }
  catch(e){ if(/Code d'accès/.test(e.message)){ $("#setup").hidden=false; $("#codeField").hidden=false; } toast(e.message) }
}
$("#slots").addEventListener("click", async e => {
  const b = e.target.closest(".check"); if(!b) return;
  const key = b.closest("[data-key]").dataset.key;
  const r = [...state.main, ...state.reps].find(x=>x.key===key); if(!r) return;
  r.done = r.done ? null : Date.now(); render();
  try{ await call("/done", { date: state.date, key, done: !!r.done }); nativeCall("reschedule"); if(state.main.every(x=>x.done)) toast("Tous les débuts de service confirmés"); }
  catch(err){ r.done = r.done ? null : Date.now(); render(); toast("Pas de réseau, réessaie") }
});
$("#offset").onchange = e => call("/settings",{offset:+e.target.value}).then(s=>{ state.settings=s; render(); nativeCall("reschedule"); toast("Réglage enregistré") }).catch(err=>toast(err.message));
$("#repeat").onchange = e => call("/settings",{repeat:+e.target.value}).then(s=>{ state.settings=s; render(); nativeCall("reschedule"); toast("Réglage enregistré") }).catch(err=>toast(err.message));

/* ---------- Import PDF ---------- */
function loadScript(src){ return new Promise((ok,ko)=>{ const s=document.createElement("script"); s.src=src; s.onload=ok; s.onerror=ko; document.head.append(s) }) }
let pdfP=null;
function pdfjs(){ if(!pdfP) pdfP=(async()=>{ const b="https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/"; await loadScript(b+"pdf.min.js"); window.pdfjsLib.GlobalWorkerOptions.workerSrc=b+"pdf.worker.min.js"; return window.pdfjsLib })(); return pdfP }
$("#pdf").addEventListener("change", async e => {
  const f = e.target.files[0]; e.target.value = ""; if(!f) return;
  try{
    toast("Lecture du planning…");
    const lib = await pdfjs();
    const doc = await lib.getDocument({ data: new Uint8Array(await f.arrayBuffer()) }).promise;
    let lines = []; for(let i=1;i<=doc.numPages;i++){ const pg=await doc.getPage(i); const tc=await pg.getTextContent(); lines.push(...pdfLines(tc.items)) }
    const days = parsePlanning(lines); const k = Object.keys(days).filter(d=>days[d].length);
    if(!k.length){ toast("Aucune prise de service trouvée dans ce PDF"); return }
    const r = await call("/planning", { days });
    toast(`Planning importé : ${k.length} jours, ${r.rows} lignes`); refresh(); nativeCall("reschedule");
  }catch(err){ toast("Impossible de lire ce PDF") }
});

/* ---------- Onglets ---------- */
let view = "service";
try{ view = localStorage.getItem("sentinel.view") || "service" }catch(e){}
function showView(v){
  view = v; try{ localStorage.setItem("sentinel.view", v) }catch(e){}
  const svc = v === "service";
  $("#today").hidden = !svc; $("#settings").hidden = !svc; $("#tasksView").hidden = v !== "tasks"; $("#qgView").hidden = v !== "qg";
  $("#notif").hidden = v === "qg";
  document.querySelectorAll(".tab").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.v === v)));
  if(v === "tasks" && window.SATasks) window.SATasks.refresh();
  window.scrollTo(0,0);
}
document.querySelectorAll(".tab").forEach(b => b.onclick = () => showView(b.dataset.v));
function badge(id,n){ const el=$(id); el.hidden=!n; el.textContent=n||"" }

/* ---------- Démarrage ---------- */
function tickClock(){ const d=new Date(); $("#clock").textContent = String(d.getHours()).padStart(2,"0")+":"+String(d.getMinutes()).padStart(2,"0") }
tickClock(); setInterval(tickClock, 15000);

async function boot(){
  cfg = load();
  if(!cfg && NATIVE){ try{ const c = JSON.parse(nativeCall("getConfig") || "{}"); if(c.key) cfg = c }catch(e){} }
  if(!cfg){ $("#setup").hidden = false; return }
  await save(cfg);
  $("#setup").hidden = true; $("#tabs").hidden = false; showView(view);
  $("#showCode").textContent = cfg.key;
  refreshNotif(); refresh(); if(window.SATasks) window.SATasks.refresh();
}
window.SA = { native: (fn,...a)=>nativeCall(fn,...a), call: (...a)=>call(...a), toast: m=>toast(m), esc, badge, get ready(){ return !!cfg } };
if(!NATIVE && "serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(()=>{});
document.addEventListener("visibilitychange", () => { if(!document.hidden && cfg) { refresh(); refreshNotif(); } });
setInterval(() => { if(!document.hidden && cfg) refresh() }, 30000);
boot();
})();
