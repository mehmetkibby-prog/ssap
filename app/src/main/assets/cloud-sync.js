(()=>{
'use strict';
const URL='https://ewqiufqngvphbxrpjote.supabase.co';
const KEY='sb_publishable_ymiszdV08BNCUvuEd1PSAw_UYlB06pg';
const SESSION='ss_cloud_session_v1';
let session=null,lastSnapshot='',busy=false,timer=null;
const hdr=(token)=>({'apikey':KEY,'Authorization':'Bearer '+token,'Content-Type':'application/json'});
const qs=(s)=>document.querySelector(s);
function canonicalKeys(){return Object.keys(localStorage).filter(k=>/^ss_/i.test(k)&&k!==SESSION)}
function readProgress(){
  let best=null;
  for(const k of canonicalKeys())try{const v=JSON.parse(localStorage.getItem(k)||'null');if(v&&typeof v==='object'&&('attempts'in v||'favorites'in v||'wrongs'in v)){if(!best||(v.attempts||0)>(best.attempts||0))best=v}}catch(e){}
  return best||{};
}
function merge(a,b){
  a=a||{};b=b||{};const out={...a,...b};
  out.attempts=Math.max(a.attempts||0,b.attempts||0);out.correct=Math.max(a.correct||0,b.correct||0);
  out.wrongs={...(a.wrongs||{}),...(b.wrongs||{})};out.favorites={...(a.favorites||{}),...(b.favorites||{})};
  out.bySubject={...(a.bySubject||{}),...(b.bySubject||{})};return out;
}
function writeProgress(cloud){
  const keys=canonicalKeys();
  if(!keys.length){localStorage.setItem('ss_s10fe_tablet_v1',JSON.stringify(cloud));return}
  for(const k of keys)try{const v=JSON.parse(localStorage.getItem(k)||'{}');if(v&&typeof v==='object'&&('attempts'in v||'favorites'in v||'wrongs'in v))localStorage.setItem(k,JSON.stringify(merge(v,cloud)))}catch(e){}
}
async function api(path,opt={}){const r=await fetch(URL+path,opt);const t=await r.text();if(!r.ok)throw new Error(t||r.statusText);return t?JSON.parse(t):null}
async function pull(){if(!session?.access_token||busy)return;busy=true;try{const rows=await api('/rest/v1/study_sync?select=state&user_id=eq.'+session.user.id,{headers:hdr(session.access_token)});if(rows?.[0]?.state){writeProgress(rows[0].state);lastSnapshot=JSON.stringify(readProgress());location.reload()}}finally{busy=false}}
async function push(){if(!session?.access_token||busy)return;const state=readProgress(),snap=JSON.stringify(state);if(snap===lastSnapshot)return;busy=true;try{await api('/rest/v1/study_sync?on_conflict=user_id',{method:'POST',headers:{...hdr(session.access_token),'Prefer':'resolution=merge-duplicates'},body:JSON.stringify({user_id:session.user.id,state,updated_at:new Date().toISOString()})});lastSnapshot=snap;setStatus('☁ Senkron')}catch(e){setStatus('☁ Hata')}finally{busy=false}}
function setStatus(t){const b=qs('#cloudSyncBtn');if(b)b.textContent=t}
function ui(){
 const b=document.createElement('button');b.id='cloudSyncBtn';b.textContent=session?'☁ Senkron':'☁ Giriş';b.style.cssText='position:fixed;right:18px;bottom:92px;z-index:9999;border:0;border-radius:999px;padding:11px 15px;background:#263b79;color:#fff;font:700 13px system-ui;box-shadow:0 8px 25px #0002';b.onclick=()=>session?push():login();document.body.appendChild(b)
}
async function login(){
 const email=prompt('Senkron hesabı e-posta:');if(!email)return;const password=prompt('Şifre (en az 6 karakter):');if(!password)return;
 try{session=await api('/auth/v1/token?grant_type=password',{method:'POST',headers:{'apikey':KEY,'Content-Type':'application/json'},body:JSON.stringify({email,password})});localStorage.setItem(SESSION,JSON.stringify(session));setStatus('☁ Senkron');await pull()}
 catch(e){if(confirm('Hesap bulunamadı. Bu bilgilerle yeni senkron hesabı oluşturulsun mu?'))try{const r=await api('/auth/v1/signup',{method:'POST',headers:{'apikey':KEY,'Content-Type':'application/json'},body:JSON.stringify({email,password})});alert('Hesap oluşturuldu. E-posta doğrulaması istenirse doğrulayıp tekrar ☁ Giriş yap.')}catch(x){alert('Hesap oluşturulamadı: '+x.message)} }
}
try{session=JSON.parse(localStorage.getItem(SESSION)||'null')}catch(e){}
window.addEventListener('DOMContentLoaded',()=>{ui();lastSnapshot=JSON.stringify(readProgress());if(session?.access_token){pull().catch(()=>{});timer=setInterval(()=>push().catch(()=>{}),4000)}});
window.addEventListener('beforeunload',()=>{push().catch(()=>{})});
})();