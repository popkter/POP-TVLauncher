const fs=require('fs'),vm=require('vm'),assert=require('assert');
let document;
class Element{
 constructor(){this.children=[];this.events={};this.dataset={};this.attributes={};this.className='';this.value='';this.hidden=false;this.classList={contains:name=>this.className.split(' ').includes(name)}}
 addEventListener(name,fn){this.events[name]=fn}
 setAttribute(name,value){this.attributes[name]=value}
 contains(node){return node===this||this.children.some(child=>child.contains(node))}
 append(...children){this.children.push(...children)}
 replaceChildren(){if(this.contains(document.activeElement)){document.activeElement=null;nodes.addressField.events.focusout?.({relatedTarget:null})}this.children=[]}
 querySelectorAll(selector){const name=selector.split(':')[0].slice(1);return this.children.flatMap(c=>[...(c.classList.contains(name)?[c]:[]),...c.querySelectorAll(selector)]).filter(c=>!selector.includes(':not')||!c.disabled)}
 querySelector(selector){return this.querySelectorAll(selector)[0]||null}
 focus(){document.activeElement=this;this.events.focus?.({})}
}
const nodes=Object.fromEntries(['addressField','address','addressHistory','historyItems','connectForm'].map(id=>[id,new Element()]));
nodes.addressField.append(nodes.address,nodes.addressHistory);nodes.addressHistory.append(nodes.historyItems);
document={activeElement:null,createElement:()=>new Element(),events:{},addEventListener(name,fn){this.events[name]=fn}};
const requests=[];
const context={$:id=>nodes[id],window:{},document,latest:{busy:false,connectionHistory:['192.168.1.2:5555','192.168.1.3:5555']},action:r=>requests.push(r)};
vm.runInNewContext(fs.readFileSync('web/history.js','utf8'),context);
nodes.address.focus();assert.equal(nodes.addressHistory.hidden,false);assert.equal(nodes.historyItems.children.length,2);
nodes.address.value='1.3';nodes.address.events.input();assert.equal(nodes.historyItems.children.length,1);
const pick=nodes.historyItems.querySelector('.history-address');pick.onclick();assert.equal(requests.pop().action,'connect');assert.equal(nodes.address.value,'192.168.1.3:5555');assert.equal(nodes.addressHistory.hidden,true);
nodes.address.value='';nodes.address.focus();
const remove=nodes.historyItems.querySelector('.history-delete');remove.focus();let stopped=false;remove.onclick({stopPropagation(){stopped=true}});assert(stopped);const deletion=requests.pop();assert.equal(deletion.action,'deleteHistory');assert.equal(deletion.address,'192.168.1.2:5555');
context.latest.connectionHistory=['192.168.1.3:5555'];context.window.renderConnectionHistory();assert.equal(nodes.addressHistory.hidden,false);assert.equal(nodes.historyItems.children.length,1);assert.equal(document.activeElement.dataset.address,'192.168.1.3:5555');
nodes.addressField.events.keydown({key:'Escape',preventDefault(){}});assert.equal(nodes.addressHistory.hidden,true);
nodes.address.focus();document.events.pointerdown({target:new Element()});assert.equal(nodes.addressHistory.hidden,true);
console.log('History UI passed: filter, connect selection, delete without connecting, focus preservation and dismissal');
