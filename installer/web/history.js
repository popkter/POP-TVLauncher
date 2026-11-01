'use strict';
(()=>{
 const field=$('addressField'), input=$('address'), popup=$('addressHistory'), items=$('historyItems');
 let opened=false,lastSignature='',rebuilding=false;
 function close(){opened=false;popup.hidden=true;input.setAttribute('aria-expanded','false');lastSignature='';}
 function render(){
  const query=input.value.trim().toLowerCase();
  const addresses=(latest?.connectionHistory||[]).filter(address=>address.toLowerCase().includes(query));
  popup.hidden=!opened||addresses.length===0;
  input.setAttribute('aria-expanded',String(!popup.hidden));
  const signature=JSON.stringify([addresses,!!latest?.busy]);
  if(signature===lastSignature)return;
  lastSignature=signature;
  const focusedAddress=document.activeElement?.dataset?.address;
  const focusedDelete=document.activeElement?.classList?.contains('history-delete');
  rebuilding=true;
  items.replaceChildren();
  for(const address of addresses){
   const row=document.createElement('div');row.className='history-row';
   const choose=document.createElement('button');choose.type='button';choose.className='history-address';choose.textContent=address;choose.dataset.address=address;choose.disabled=!!latest?.busy;
   choose.setAttribute('aria-label','连接 '+address);
   choose.onclick=()=>{if(latest?.busy)return;input.value=address;close();action({action:'connect',address});};
   const remove=document.createElement('button');remove.type='button';remove.className='history-delete';remove.textContent='×';remove.dataset.address=address;remove.disabled=!!latest?.busy;
   remove.setAttribute('aria-label','删除连接记录 '+address);remove.title='删除此记录';
   remove.onclick=e=>{e.stopPropagation();if(!latest?.busy)action({action:'deleteHistory',address});};
   row.append(choose,remove);items.append(row);
  }
  if(focusedAddress && opened){
   const buttons=[...items.querySelectorAll(focusedDelete?'.history-delete':'.history-address')];
   (buttons.find(button=>button.dataset.address===focusedAddress)||buttons[0]||input).focus();
  }
  rebuilding=false;
 }
 input.addEventListener('focus',()=>{opened=true;render();});
 input.addEventListener('input',()=>{opened=true;render();});
 input.addEventListener('keydown',e=>{
  if(e.key==='ArrowDown'){opened=true;render();const first=items.querySelector('.history-address:not(:disabled)');if(!popup.hidden&&first){e.preventDefault();first.focus();}}
 });
 field.addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();input.focus();close();}});
 field.addEventListener('focusout',e=>{if(!rebuilding&&!field.contains(e.relatedTarget))close();});
 document.addEventListener('pointerdown',e=>{if(!field.contains(e.target))close();});
 $('connectForm').addEventListener('submit',close);
 window.renderConnectionHistory=render;
 render();
})();
