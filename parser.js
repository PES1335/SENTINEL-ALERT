/* Shared: turns pdf.js text content into lines, then parses planning rows */
function pdfLines(items){
  const rows=[];
  for(const it of items){ if(!it.str||!it.str.trim()) continue; const y=it.transform[5], x=it.transform[4];
    let r=rows.find(r=>Math.abs(r.y-y)<2.5); if(!r){ r={y,items:[]}; rows.push(r) } r.items.push({x,w:it.width,s:it.str}) }
  rows.sort((a,b)=>b.y-a.y);
  return rows.map(r=>{ r.items.sort((a,b)=>a.x-b.x); let out="", end=null;
    for(const it of r.items){ if(end!==null){ const gap=it.x-end; out+= gap>6?"   ":gap>0.8?" ":"" } out+=it.s; end=it.x+it.w }
    return out });
}
function parsePlanning(lines){
  const days={}; let date=null, pend=null;
  for(const raw of lines){
    const line=raw.replace(/ /g," ");
    const dm=line.match(/Date des prises de service\s*:\s*(\d{2})\/(\d{2})\/(\d{4})/);
    if(dm){ date=`${dm[3]}-${dm[2]}-${dm[1]}`; days[date]=days[date]||[]; pend=null; continue }
    if(!date) continue;
    if(/^\s*(0\d{9}\s*)+$/.test(line)){ const list=days[date]; const last=list[list.length-1]; const ps=line.match(/0\d{9}/g); if(last&&!last.tel) last.tel=ps[ps.length-1]; continue }
    const lead=line.match(/^\s*(\d{1,2}):(\d{2})/);
    if(lead) pend=lead[1].padStart(2,"0")+":"+lead[2];
    const em=line.match(/(\d{1,2}):(\d{2})\s*$/);
    const km=line.match(/(Yorker|NOCIBE)/i);
    if(!em||!km||!pend) continue;
    const end=em[1].padStart(2,"0")+":"+em[2];
    let body=line.slice(0,em.index);
    const kIdx=km.index;
    const brand=/yorker/i.test(km[1])?"New Yorker":"Nocibé";
    const tail=body.slice(kIdx+km[1].length);
    const phones=[]; tail.replace(/\b0\d{9}\b/g,(p,i)=>{ phones.push({p,i}); return p });
    const segs=[]; let re=/\S+(?: \S+)*/g, m;
    const clean=tail.replace(/\b0\d{9}\b/g,m=>" ".repeat(m.length));
    while((m=re.exec(clean))) segs.push({t:m[0].trim(),i:m.index});
    if(!segs.length) continue;
    let siteName=segs[0].t; const agentSeg=segs.slice(1);
    let agent=agentSeg.map(s=>s.t).join(" ").trim();
    // site names occasionally run into the agent column with a single space; NOCIBE sites stay uppercase so keep as is
    const agentStart=agentSeg.length?agentSeg[0].i:Infinity;
    let telSite="", tel="";
    phones.forEach(ph=>{ if(ph.i<agentStart) telSite=ph.p; else tel=ph.p });
    if(!tel && phones.length && !telSite) tel=phones[phones.length-1].p;
    siteName=siteName.replace(/Sarcellles/i,"Sarcelles").replace(/\s+/g," ").trim();
    if(brand==="Nocibé") siteName=siteName.toLowerCase().replace(/(^|[\s-])\S/g,c=>c.toUpperCase()).replace(/\bSur\b/g,"sur").replace(/\bSous\b/g,"sous").replace(/\bSt\b/g,"Saint");
    const sub=/^S\s+securiparis\s+/i.test(agent);
    agent=agent.replace(/^S\s+securiparis\s+/i,"");
    const vacant=/^F\s/.test(agent);
    const shift=+pend.slice(0,2)<12&&+end.slice(0,2)<=13?"Matin":+pend.slice(0,2)>=13?"Après-midi":"Journée";
    days[date].push({start:pend,end,site:brand+" "+siteName,agent:vacant?"":agent,placeholder:vacant?agent:"",sub,tel,telSite,shift});
    pend=null;
  }
  for(const d in days){ const seen=new Set(); days[d]=days[d].filter(r=>{ const k=r.start+r.site+r.agent+r.placeholder; if(seen.has(k)) return false; seen.add(k); return true }) }
  return days;
}

