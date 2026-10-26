// Sentinel Alertes — objectifs (alertes envoyées par le serveur, même appli fermée)
(() => {
const $ = s => document.querySelector(s);
const pad = n => String(n).padStart(2,"0");
const PRIO = ["Normale","Importante","Urgente"];
const REP = {none:"",day:"Chaque jour",weekday:"En semaine",week:"Chaque semaine"};
let tasks = [], editing = null, loaded = false;

const uid = () => Math.random().toString(36).slice(2,10) + Date.now().toString(36);
const dayStart = d => { const x=new Date(d); x.setHours(0,0,0,0); return x.getTime() };
const fmtTime = ms => { const d=new Date(ms); return pad(d.getHours())+":"+pad(d.getMinutes()) };
function fmtDay(ms){
  const diff = Math.round((dayStart(ms)-dayStart(Date.now()))/864e5);
  if(diff===0) return "Aujourd'hui"; if(diff===1) return "Demain"; if(diff===-1) return "Hier";
  return new Date(ms).toLocaleDateString("fr-FR",{weekday:"short",day:"numeric",month:"short"});
}
function nextDue(t){
  const d = new Date(t.due);
  if(t.repeat==="day") d.setDate(d.getDate()+1);
  else if(t.repeat==="week") d.setDate(d.getDate()+7);
  else if(t.repeat==="weekday"){ do{ d.setDate(d.getDate()+1) }while(d.getDay()===0||d.getDay()===6) }
  return d.getTime();
}
function remindLabel(r){ return r===0?"à l'heure":r===1440?"la veille":r>=60?(r/60)+" h avant":r+" min avant" }

/* « Rapport La Défense 14h30 », « demain 9h » */
function parseQuick(text){
  let title = text.trim(), due = null, shift = 0;
  if(/\bdemain\b/i.test(title)){ shift=1; title=title.replace(/\bdemain\b/i,"") }
  title = title.replace(/\baujourd'hui\b/i,"");
  const m = title.match(/\b(?:à\s*)?([01]?\d|2[0-3])\s*(?:h|:)\s*([0-5]\d)?\b/i);
  const d = new Date();
  if(m){ d.setDate(d.getDate()+shift); d.setHours(+m[1], m[2]?+m[2]:0, 0, 0); if(!shift && d.getTime()<Date.now()-60000) d.setDate(d.getDate()+1); due=d.getTime(); title=title.replace(m[0],"") }
  else if(shift){ d.setDate(d.getDate()+1); d.setHours(9,0,0,0); due=d.getTime() }
  title = title.replace(/\s{2,}/g," ").trim();
  return { title: title || text.trim(), due };
}

async function save(t){
  const i = tasks.findIndex(x=>x.id===t.id); if(i<0) tasks.push(t); else tasks[i]=t; render();
  try{ await SA.call("/tasks", t) }catch(e){ SA.toast("Pas de réseau : objectif non enregistré") }
}
async function del(id){
  tasks = tasks.filter(x=>x.id!==id); render();
  try{ await SA.call("/tasks/delete", {id}) }catch(e){ SA.toast("Pas de réseau, réessaie") }
}
function complete(t){
  if(t.repeat && t.repeat!=="none" && t.due){
    let d = nextDue(t); while(d < Date.now()) d = nextDue({...t, due:d});
    save({...t, due:d, snooze:null, firedAt:null}); SA.toast("Accompli · prochain : "+fmtDay(d)+" "+fmtTime(d));
  } else { save({...t, done:true, doneAt:Date.now(), snooze:null}); SA.toast("Objectif accompli · +100 XP") }
}

function item(t){
  const late = t.due && !t.done && t.due < Date.now();
  const meta = [];
  if(t.due) meta.push(`<span class="${late?"late":"t"}">${fmtDay(t.due)} · ${fmtTime(t.due)}</span>`);
  if(t.remind>=0 && t.due && !t.done) meta.push(`<span>🔔 ${t.snooze?"reportée "+fmtTime(t.snooze):remindLabel(t.remind)}</span>`);
  if(t.repeat && t.repeat!=="none") meta.push(`<span>↻ ${REP[t.repeat]}</span>`);
  if(t.prio>0) meta.push(`<span>${PRIO[t.prio]}</span>`);
  return `<div class="item${t.done?" done":""}" data-prio="${t.prio}" data-id="${SA.esc(t.id)}">
    <button class="check" data-act="toggle" aria-label="${t.done?"Rouvrir":"Accomplir"} ${SA.esc(t.title)}"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="3.5" aria-hidden="true"><path d="M5 12l5 5 9-10"/></svg></button>
    <div class="body" data-act="edit"><div class="who">${SA.esc(t.title)}</div>${meta.length?`<div class="meta">${meta.join("")}</div>`:""}</div>
    <button class="more" data-act="edit" aria-label="Modifier"><svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg></button>
  </div>`;
}
function render(){
  const now = Date.now(), end = dayStart(now)+864e5;
  const open = tasks.filter(t=>!t.done), done = tasks.filter(t=>t.done).sort((a,b)=>(b.doneAt||0)-(a.doneAt||0));
  const byDue = (a,b)=>(a.due||Infinity)-(b.due||Infinity)||b.prio-a.prio;
  const late = open.filter(t=>t.due&&t.due<now).sort(byDue);
  const today = open.filter(t=>t.due&&t.due>=now&&t.due<end).sort(byDue);
  const soon = open.filter(t=>t.due&&t.due>=end).sort(byDue);
  const nodate = open.filter(t=>!t.due).sort((a,b)=>b.prio-a.prio);
  const sec = (label,list,cls="") => list.length ? `<section class="sec ${cls}"><h2><span>${label}</span><span>${list.length}</span></h2><div class="list">${list.map(item).join("")}</div></section>` : "";
  let html = sec("Hors délai",late,"late")+sec("Aujourd'hui",today)+sec("À venir",soon)+sec("Sans date",nodate);
  if(done.length) html += `<details><summary>Accomplis · ${done.length}</summary><div class="list">${done.slice(0,30).map(item).join("")}</div></details>`;
  if(!tasks.length) html = `<p class="empty">${loaded?"Aucun objectif. Écris-en un en haut, avec une heure pour avoir une alerte : « Rapport La Défense 14h », « Appeler Karim demain 9h30 ».":"Chargement…"}</p>`;
  $("#taskList").innerHTML = html;
  SA.badge("#badgeTasks", late.length);
}
async function refresh(){
  if(!SA.ready) return;
  try{ const r = await SA.call("/tasks"); tasks = r.tasks || []; loaded = true; render() }
  catch(e){ loaded = true; render() }
}

/* Fiche */
function setPrio(v){ editing.prio=v; document.querySelectorAll("#fPrio button").forEach(b=>b.setAttribute("aria-pressed", String(+b.dataset.v===v))) }
document.querySelectorAll("#fPrio button").forEach(b=>b.onclick=()=>setPrio(+b.dataset.v));
function openSheet(t){
  const exists = !!t && tasks.some(x=>x.id===t.id);
  editing = t ? {...t} : {id:uid(),title:"",due:null,remind:15,repeat:"none",prio:0,done:false,created:Date.now()};
  $("#sheetTitle").textContent = exists ? "Modifier l'objectif" : "Nouvel objectif";
  $("#fTitle").value = editing.title;
  if(editing.due){ const d=new Date(editing.due); $("#fDate").value=`${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`; $("#fTime").value=fmtTime(editing.due) }
  else { $("#fDate").value=""; $("#fTime").value="" }
  $("#fRemind").value = String(editing.remind); $("#fRepeat").value = editing.repeat||"none"; setPrio(editing.prio||0);
  $("#delBtn").hidden = !exists; $("#scrim").hidden = false; setTimeout(()=>$("#fTitle").focus(), 60);
}
function closeSheet(){ $("#scrim").hidden = true; editing = null }
$("#cancelBtn").onclick = closeSheet;
$("#scrim").addEventListener("click", e => { if(e.target.id==="scrim") closeSheet() });
$("#delBtn").onclick = () => { const id = editing.id; closeSheet(); del(id); SA.toast("Objectif supprimé") };
$("#sheet").addEventListener("submit", e => {
  e.preventDefault();
  const title = $("#fTitle").value.trim(); if(!title) return;
  let due = null; const d = $("#fDate").value, tm = $("#fTime").value;
  if(d||tm){ const base = d ? new Date(d+"T00:00") : new Date(); const [h,m]=(tm||"09:00").split(":"); base.setHours(+h,+m,0,0); due = base.getTime() }
  const before = tasks.find(x=>x.id===editing.id);
  const t = {...editing, title, due, remind:+$("#fRemind").value, repeat:$("#fRepeat").value};
  if(!before || before.due!==t.due || before.remind!==t.remind){ t.firedAt=null; t.snooze=null }
  closeSheet(); save(t); SA.toast(before ? "Objectif mis à jour" : (t.due && t.remind>=0 ? "Objectif ajouté · alerte "+fmtTime(t.due - t.remind*60000) : "Objectif ajouté"));
});
$("#quick").addEventListener("submit", e => {
  e.preventDefault(); const v = $("#quickInput").value.trim(); if(!v) return;
  const p = parseQuick(v); $("#quickInput").value = "";
  openSheet({id:uid(), title:p.title, due:p.due, remind:p.due?15:-1, repeat:"none", prio:0, done:false, created:Date.now()});
});
$("#taskList").addEventListener("click", e => {
  const el = e.target.closest("[data-act]"); if(!el) return;
  const t = tasks.find(x=>x.id===el.closest("[data-id]").dataset.id); if(!t) return;
  if(el.dataset.act==="toggle") t.done ? save({...t, done:false, doneAt:null}) : complete(t);
  else openSheet(t);
});
document.addEventListener("keydown", e => { if(e.key==="Escape" && !$("#scrim").hidden) closeSheet() });
document.addEventListener("visibilitychange", () => { if(!document.hidden) refresh() });
setInterval(() => { if(!document.hidden) render() }, 60000);

window.SATasks = { refresh };
refresh();
})();
