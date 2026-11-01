'use strict';
const $=id=>document.getElementById(id), token=location.hash.slice(1)||sessionStorage.getItem('ptv-session')||'';if(token)sessionStorage.setItem('ptv-session',token);history.replaceState(null,'',location.pathname);let latest=null,pending=null,lastDevices='',lastChecks='',stopped=false;
async function action(data){try{const r=await fetch('/api/action',{method:'POST',headers:{'Content-Type':'application/json','X-PTV-Token':token},body:JSON.stringify(data)});if(!r.ok)throw new Error(await r.text());if(data.action!=='quit')await poll();}catch(e){$('error').textContent=e.message;$('error').hidden=false;}}
function confirmAction(title,body,fn,label='继续'){$('restoreChoice').hidden=true;$('dialogTitle').textContent=title;$('dialogBody').textContent=body;$('confirmDialog').textContent=label;$('cancelDialog').hidden=!fn;pending=fn;$('dialog').showModal();}
function closeDialog(){$('dialog').close();pending=null;}
$('dismiss').onclick=closeDialog;$('cancelDialog').onclick=closeDialog;$('confirmDialog').onclick=()=>{const f=pending;closeDialog();if(f)f();};
$('connectForm').onsubmit=e=>{e.preventDefault();action({action:'connect',address:$('address').value});};
$('scan').onclick=()=>action({action:'scan'});$('devices').onchange=()=>{if($('devices').value)action({action:'select',serial:$('devices').value});};
$('pair').onclick=()=>{action({action:'pair',address:$('pairAddress').value,code:$('pairCode').value});$('pairCode').value='';};
$('install').onclick=()=>confirmAction('一键配置并启用 PTV','已安装 PTV 时保留当前版本、布局与壁纸，重新启用桌面和快捷功能；未安装时自动安装。'+(latest?.tv?.bridgeEligible?'\n此电视自动使用小米兼容模式：停用原生桌面并设置 PTV 系统入口。':''),()=>action({action:'configure'}),'一键配置');
$('installOnly').onclick=()=>confirmAction('安装／更新 PTV','安装助手内置的 PTV 版本，并保留已有数据。此操作不配置默认桌面或快捷服务；安装完成后可点击“一键配置”启用。如果电视上的版本更新或签名不同，安装可能被系统拒绝，现有数据不会被清除。',()=>action({action:'installOnly'}),'安装／更新');
$('homeSettings').onclick=()=>action({action:'homeSettings'});
$('verify').onclick=()=>action({action:'verify'});
$('reboot').onclick=()=>confirmAction('重启电视验证','电视将立即重启。等待开机后重新连接，再点击“重新检查”。实体遥控器与开机画面需要你实际确认。',()=>action({action:'reboot'}),'重启电视');
$('restore').onclick=()=>confirmAction('恢复安装前备份（不一定是小米桌面）','如果安装前已经使用 PTV 或 HomeBridge，此操作仍会恢复到 PTV。要回到小米原生桌面，请取消并使用下方“一键还原原生系统”。\n恢复本机备份中的默认桌面与 PTV 快捷服务授权。安装后新增的其他无障碍服务会保留。PTV 与壁纸数据不会删除。',()=>action({action:'restore'}),'恢复配置');
$('restoreNative').onclick=()=>{
 confirmAction('还原小米原生桌面','恢复小米原生桌面，关闭 PTV 快捷功能和授权。默认仅停用 PTV 和兼容入口，保留布局、自定义壁纸及偏好，之后点击“一键配置”即可重新启用。其他应用、账号与电视设置保留。',()=>action({action:'restoreNative',confirmNative:true,removePTV:$('removePTV').checked}),'还原并保留数据');
 $('removePTV').checked=false;$('restoreChoice').hidden=false;
};
$('removePTV').onchange=()=>{$('confirmDialog').textContent=$('removePTV').checked?'还原、卸载并删除数据':'还原并保留数据';};
$('about').onclick=()=>confirmAction('PTV 安装助手 · 预览版','内置 PTV 1.3.14 · 支持电脑安装配置。当前 APK 使用开发签名，EXE 未做 Windows 代码签名。\nPTV：PolyForm Noncommercial 1.0.0\nADB：Google 官方 Platform Tools，相关许可随程序保存在本机 payload 目录。\n界面参考 Apple HIG 的层级、对齐和控件一致性，非 Apple 产品。',null,'知道了');
$('saveLogs').onclick=()=>{if(!latest)return;const blob=new Blob([JSON.stringify({time:new Date().toISOString(),...latest},null,2)],{type:'application/json'}),a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='PTV-安装记录.json';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000);};
$('quit').onclick=()=>{if(latest?.busy)return;confirmAction('退出安装助手','将关闭安装助手窗口并结束后台进程，电视配置会保留。',async()=>{await action({action:'quit'});stopped=true;window.close();$('phase').textContent='安装助手已退出，可以关闭窗口';},'退出');};
document.querySelectorAll('[data-scroll]').forEach(b=>b.onclick=()=>{document.querySelectorAll('.nav-item').forEach(n=>n.classList.toggle('active',n===b));$(b.dataset.scroll).scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth',block:'start'});});
function render(s){latest=s;const tv=s.tv,busy=s.busy;for(const id of ['connectButton','scan','pair','devices','address','pairAddress','pairCode','bridge','quit'])$(id).disabled=busy;for(const id of ['install','installOnly','verify','reboot','homeSettings'])$(id).disabled=busy||!tv;$('restore').disabled=busy||!tv||!s.backup;$('restoreNative').disabled=busy||!tv?.bridgeEligible;$('nativeHint').textContent=!tv?'连接电视后检查是否支持。':tv.bridgeEligible?'此电视支持一键还原；开始前会再次确认删除范围。':'此型号或固件尚未适配原生还原，可使用恢复安装前配置。';
 $('phase').textContent=s.phase;$('progress').style.width=s.progress+'%';$('percentage').textContent=s.progress+'%';document.querySelector('[role=progressbar]').setAttribute('aria-valuenow',s.progress);$('error').hidden=!s.error;$('error').textContent=s.error||'';
 $('connectionState').classList.toggle('connected',!!tv);$('connectionState').lastChild.textContent=tv?'已连接':'尚未连接';$('deviceInfo').hidden=!tv;$('compatRow').hidden=!tv?.bridgeEligible;$('bridge').checked=!!tv?.bridgeEligible;$('bridge').disabled=true;if(tv){$('deviceModel').textContent=tv.manufacturer+' '+tv.model;$('deviceVersion').textContent='Android '+tv.android+' · '+tv.serial+' · '+tv.firmware;}
 const ds=JSON.stringify(s.devices);if(ds!==lastDevices){lastDevices=ds;$('devices').replaceChildren(new Option('选择 USB 或已连接的电视',''));for(const d of s.devices||[]){const suffix={device:'已连接',unauthorized:'请在电视上授权',offline:'离线'}[d.state]||d.state;$('devices').add(new Option(d.serial+' · '+suffix,d.serial));}if(tv)$('devices').value=tv.serial;}
 const cs=JSON.stringify(s.checks);if(cs!==lastChecks){lastChecks=cs;$('checks').replaceChildren();for(const c of s.checks||[]){const row=document.createElement('div');row.className='check-row '+c.status;const mark=document.createElement('span');mark.className='mark';mark.textContent=c.status==='ok'?'✓':c.status==='manual'?'·':'!';const content=document.createElement('div'),title=document.createElement('strong'),detail=document.createElement('p');title.textContent=c.name;detail.textContent=c.detail;content.append(title,detail);row.append(mark,content);$('checks').append(row);}}
 $('logs').textContent=(s.logs||[]).join('\n')||'尚无操作记录';
 if(window.renderConnectionHistory)window.renderConnectionHistory();
}
async function poll(){if(stopped)return;try{const r=await fetch('/api/state',{headers:{'X-PTV-Token':token}});if(!r.ok)throw new Error('无法读取助手状态');render(await r.json());}catch(e){$('error').hidden=false;$('error').textContent='无法连接本机安装助手。请重新运行 EXE。';}}
poll();setInterval(poll,900);

window.addEventListener('pagehide',()=>{
 if(stopped)return;
 fetch('/api/action',{method:'POST',headers:{'Content-Type':'application/json','X-PTV-Token':token},body:JSON.stringify({action:'windowClosed'}),keepalive:true}).catch(()=>{});
});
window.addEventListener('beforeunload',e=>{if(latest?.busy){e.preventDefault();e.returnValue='';}});
