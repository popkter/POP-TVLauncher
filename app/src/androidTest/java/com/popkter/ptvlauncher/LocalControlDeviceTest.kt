package com.popkter.ptvlauncher

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalControlDeviceTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private fun request(value:JSONObject):JSONObject {
        val operation=when(value.getString("action")){"probe_wifi"->9;"bluetooth_state"->10;"forget_wifi"->5;else->18}
        val done=CountDownLatch(1);var response=""
        instrument.runOnMainSync{NetworkBridgeClient(instrument.targetContext).request("","","",operation=operation,networkId=value.optInt("id",-1)){response=it;done.countDown()}}
        check(done.await(10,TimeUnit.SECONDS))
        return runCatching{JSONObject(response)}.getOrElse{JSONObject().put("ok",response=="已忘记网络").put("message",response)}
    }
    @Test fun foreignOwnedWifiCanBeForgottenWithoutDisconnectingCurrentNetwork(){
        instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=launchTestHome();Thread.sleep(800)
        val services=ControlCenterServices(activity){}
        val before=services.connectedSsid()
        val probe=request(JSONObject().put("action","probe_wifi"))
        assertTrue(probe.toString(),probe.getBoolean("ok"))
        assertEquals(2000,probe.getInt("creatorUid"))
        val id=probe.getInt("id");val ssid=probe.getString("ssid")
        assertTrue(ssid.startsWith("PTV-system-probe-"))
        try {
            val done=CountDownLatch(1);var result=""
            instrument.runOnMainSync{NetworkBridgeClient(activity).request("","","",operation=5,networkId=id){result=it;done.countDown()}}
            assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals("已忘记网络",result)
            val listed=CountDownLatch(1);var remains=true
            instrument.runOnMainSync{NetworkBridgeClient(activity).savedNetworks{remains=it.any{n->n.id==id};listed.countDown()}}
            assertTrue(listed.await(8,TimeUnit.SECONDS));assertFalse(remains)
            assertEquals(before,services.connectedSsid())
        }finally {request(JSONObject().put("action","forget_wifi").put("id",id).put("ssid",ssid))}
    }
    @Test fun unsupportedCommandsAreRejected(){
        assertFalse(request(JSONObject().put("action","shell").put("command","id")).getBoolean("ok"))
        assertFalse(request(JSONObject().put("action","forget_wifi").put("id",-1)).getBoolean("ok"))
    }
    @Test fun bluetoothSnapshotAndDevicePagesDoNotChangeConnections(){
        instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val snapshot=request(JSONObject().put("action","bluetooth_state"))
        assertTrue(snapshot.toString(),snapshot.getBoolean("ok"))
        val devices=snapshot.getJSONArray("devices")
        val connected=(0 until devices.length()).map{devices.getJSONObject(it)}.filter{it.getInt("state")==BluetoothProfile.STATE_CONNECTED}.map{it.getString("address")}.toSet()
        val activity=launchTestHome();Thread.sleep(900)
        instrument.runOnMainSync{activity.surface.show(null)};Thread.sleep(500)
        instrument.runOnMainSync{activity.surface.show("settings")};Thread.sleep(1200)
        instrument.runOnMainSync{activity.surface.findViewWithTag<View>("control:bluetooth").performClick()};Thread.sleep(1100)
        for(index in 0 until devices.length()) {
            val device=devices.getJSONObject(index);val address=device.getString("address")
            instrument.runOnMainSync{assertNotNull(activity.surface.findViewWithTag<View>("control:row:bluetooth:$address"));activity.surface.findViewWithTag<View>("control:row:bluetooth:$address").performClick()}
            Thread.sleep(250)
            instrument.runOnMainSync {
                if(device.getInt("bond")==BluetoothDevice.BOND_BONDED)assertNotNull(activity.surface.findViewWithTag<View>("control:row:忽略设备"))
                assertNotNull(activity.surface.findViewWithTag<View>(if(address in connected)"control:row:断开连接" else "control:row:连接"))
                activity.surface.back()
            };Thread.sleep(250)
            instrument.runOnMainSync{assertEquals("control:row:bluetooth:$address",activity.surface.findFocus()?.tag)}
        }
        val after=request(JSONObject().put("action","bluetooth_state")).getJSONArray("devices")
        val stillConnected=(0 until after.length()).map{after.getJSONObject(it)}.filter{it.getInt("state")==BluetoothProfile.STATE_CONNECTED}.map{it.getString("address")}.toSet()
        assertEquals(connected,stillConnected)
    }
}
