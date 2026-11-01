package com.popkter.ptvlauncher

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class NetworkBridgeDeviceTest {
    @Test fun currentSavedNetworkCanBeReselectedWithoutSubmittingPassword() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val activity=launchTestHome();Thread.sleep(800)
        val services=ControlCenterServices(activity){}
        val ssid=services.connectedSsid()
        assertNotNull("Connected Wi-Fi must be readable",ssid)
        val bridge=NetworkBridgeClient(activity)
        val listed=java.util.concurrent.CountDownLatch(1)
        var saved=emptyList<NetworkBridgeClient.SavedNetwork>()
        instrument.runOnMainSync{bridge.savedNetworks{saved=it;listed.countDown()}}
        assertTrue(listed.await(8,java.util.concurrent.TimeUnit.SECONDS))
        val current=saved.first{it.ssid==ssid}
        val done=java.util.concurrent.CountDownLatch(1);var result=""
        instrument.runOnMainSync{bridge.request("","","",operation=4,networkId=current.id){result=it;done.countDown()}}
        assertTrue(done.await(8,java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("正在重新连接…",result)
        Thread.sleep(1500)
        assertEquals("Reselecting current Wi-Fi must preserve its connection",ssid,services.connectedSsid())
    }
    @Test fun signedBridgeCanCreateAndRemoveWifiConfigurationWithoutSystemUi() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val automation=instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.grantRuntimePermission(NetworkBridgeClient.PACKAGE,android.Manifest.permission.ACCESS_FINE_LOCATION)
        automation.grantRuntimePermission(NetworkBridgeClient.PACKAGE,android.Manifest.permission.ACCESS_COARSE_LOCATION)
        val done=java.util.concurrent.CountDownLatch(1)
        var response=""
        instrument.runOnMainSync {
            val bridge=NetworkBridgeClient(context)
            assertTrue(bridge.installed());assertTrue(bridge.authorized())
            bridge.request("","","open",operation=2){response=it;done.countDown()}
        }
        assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("直连配置接口可用",response)
    }
}
