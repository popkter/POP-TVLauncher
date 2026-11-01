package com.popkter.ptvlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Authoritative profile/ACL state plus asynchronous operations. Accepted is never shown as success. */
@SuppressLint("MissingPermission")
internal class ControlCenterBluetooth(private val context:Context,private val bridge:NetworkBridgeClient,private val changed:()->Unit) {
    data class Device(val address:String,val name:String,val bond:Int,val state:Int,val battery:Int=-1,val deviceClass:Int=0) {
        val connected get()=state==BluetoothProfile.STATE_CONNECTED
        val isInput get()=(deviceClass and 0x1f00)==0x0500
    }
    private data class Pending(val operation:Int,val started:Long)
    private val handler=Handler(Looper.getMainLooper())
    private val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter
    private val discovered=linkedMapOf<String,BluetoothDevice>()
    private var saved=emptyList<Device>()
    private val pending=mutableMapOf<String,Pending>()
    private var querying=false
    private var running=false
    var ready=false;private set
    var enabled=false;private set
    var available=adapter!=null;private set
    var status="";private set
    val scanning get()=runCatching{adapter?.isDiscovering==true}.getOrDefault(false)
    private val poll=object:Runnable {override fun run(){if(running){refresh();handler.postDelayed(this,if(pending.isEmpty())3500 else 1000)}}}
    fun start(){if(running)return;running=true;handler.post(poll)}
    fun stop(){running=false;handler.removeCallbacksAndMessages(null);pending.clear()}
    fun found(device:BluetoothDevice){runCatching{discovered[device.address]=device};refresh()}
    fun allowed()=Build.VERSION.SDK_INT<31 || ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    fun devices():List<Device> {
        if(!allowed())return emptyList()
        val current=runCatching{adapter?.bondedDevices.orEmpty()+discovered.values}.getOrDefault(emptySet())
        val values=current.map { device->saved.firstOrNull{it.address==device.address}?.copy(bond=device.bondState)
            ?: Device(device.address,device.name?.takeIf{it.isNotBlank()}?:"蓝牙设备",device.bondState,BluetoothProfile.STATE_DISCONNECTED) }
        return (saved+values).associateBy{it.address}.values.sortedWith(compareByDescending<Device>{it.connected}.thenByDescending{it.bond==BluetoothDevice.BOND_BONDED}.thenBy{it.name})
    }
    fun busy(address:String)=address in pending
    fun operation(address:String)=pending[address]?.operation
    fun summary(device:Device):String {
        val action=pending[device.address]?.operation
        return when {
            action==11->"正在连接…";action==12->"正在断开…";action==13->"正在忽略…";action==14->"正在取消配对…"
            device.bond==BluetoothDevice.BOND_BONDING || action==15->"正在配对…"
            device.connected->"已连接"+(if(device.battery in 0..100)" · 电量 ${device.battery}%" else "")
            device.state==BluetoothProfile.STATE_CONNECTING->"正在连接…"
            device.state==BluetoothProfile.STATE_DISCONNECTING->"正在断开…"
            device.bond==BluetoothDevice.BOND_BONDED->"已配对 · 未连接"
            else->"可配对"
        }
    }
    fun refresh() {
        if(querying || !running || !allowed())return
        querying=true
        bridge.request("","","",operation=10){response->
            querying=false;if(!running)return@request
            val value=runCatching{JSONObject(response)}.getOrNull()
            ready=value?.optBoolean("ok")==true
            enabled=if(ready)value!!.optBoolean("enabled") else runCatching{adapter?.isEnabled==true}.getOrDefault(false)
            available=value?.optBoolean("available",adapter!=null)?: (adapter!=null)
            if(ready){
                val list=value!!.optJSONArray("devices")
                saved=(0 until (list?.length()?:0)).map { index->list!!.getJSONObject(index).let{Device(it.getString("address"),it.optString("name").ifBlank{"蓝牙设备"},it.getInt("bond"),it.getInt("state"),it.optInt("battery",-1),it.optInt("deviceClass",0))} }
            }
            val now=SystemClock.uptimeMillis()
            pending.toMap().forEach { (address,operation)->
                val device=devices().firstOrNull{it.address==address}
                val done=when(operation.operation){11->device?.connected==true;12->device?.state==BluetoothProfile.STATE_DISCONNECTED;13,14->device==null || device.bond==BluetoothDevice.BOND_NONE;15->device?.bond==BluetoothDevice.BOND_BONDED;16->enabled;17->!enabled;else->false}
                if(done){pending.remove(address);status=when(operation.operation){11->"设备已连接";12->"设备已断开";13->"已忽略设备";14->"已取消配对";15->"配对完成，正在连接…";16->"蓝牙已开启";else->"蓝牙已关闭"};if(operation.operation==15 && device!=null && !device.connected)action(device,11)}
                else if(now-operation.started>35000){pending.remove(address);status="操作未完成，请确认设备已开启并在附近后重试"}
            }
            changed()
        }
    }
    fun action(device:Device?,operation:Int):String {
        if(!ready)return "本地管理服务未激活，请通过 PTV 安装工具重新激活"
        val address=device?.address.orEmpty()
        if(address in pending && operation!=14 && !(operation==12&&pending[address]?.operation==11))return "操作正在进行，请稍候"
        pending[address]=Pending(operation,SystemClock.uptimeMillis())
        status=when(operation){11->"正在连接…";12->"正在断开…";13->"正在忽略设备…";14->"正在取消配对…";15->"正在配对…";else->"正在切换蓝牙状态…"}
        bridge.request("","","",operation=operation,address=address){result->
            if(!running)return@request
            if(pending[address]?.operation!=operation){refresh();return@request}
            status=result
            if(!result.startsWith("正在"))pending.remove(address)
            refresh();changed()
        }
        changed();return status
    }
    fun clearStatus(){status=""}
}
