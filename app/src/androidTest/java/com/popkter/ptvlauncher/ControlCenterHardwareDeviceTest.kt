package com.popkter.ptvlauncher

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

/** Run with the launcher's UID; shell privileges do not prove application access. */
class ControlCenterHardwareDeviceTest {
    @Test fun tunerSelectionOpensTheActualTelevisionPlayerFromApplicationUid() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val automation=instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=launchTestHome()
        val services=ControlCenterServices(activity){}
        val tuners=services.sourceList().filter{!it.isPassthroughInput}
        assertTrue(tuners.isNotEmpty())
        tuners.forEach {input->
            instrument.runOnMainSync{assertTrue("Tuner action must be accepted: ${input.id}",services.openSource(input))}
            Thread.sleep(1800)
            assertEquals("Tuner must open the real TV player","com.xiaomi.mitv.tvplayer",automation.rootInActiveWindow?.packageName?.toString())
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(500)
        }
    }
    @Test fun authorizedSystemBrightnessWritesAndRestoresValue() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val resolver=context.contentResolver
        val original=android.provider.Settings.System.getInt(resolver,"screen_brightness")
        val mode=android.provider.Settings.System.getInt(resolver,"screen_brightness_mode",0)
        val services=ControlCenterServices(context){}
        assertTrue("Installer must grant system settings access",services.canSetBrightness())
        try {
            assertTrue(services.brightness(55))
            assertEquals(55,services.brightness())
            Thread.sleep(2500)
        }finally {
            android.provider.Settings.System.putInt(resolver,"screen_brightness",original)
            android.provider.Settings.System.putInt(resolver,"screen_brightness_mode",mode)
        }
    }
    @Test fun selectedHdmiCreatesRealInputSession() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val inputs=context.getSystemService(android.media.tv.TvInputManager::class.java).tvInputList.filter{it.isPassthroughInput}
        assertTrue(inputs.isNotEmpty())
        inputs.forEach {input->
        val activity=instrument.startActivitySync(android.content.Intent(context,SourcePlaybackActivity::class.java).putExtra("input_id",input.id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as SourcePlaybackActivity
        try {
            // Cold vendor input services and HDMI handshakes can exceed five seconds.
            repeat(150){if(activity.videoState==null&&!activity.connectionFailed)Thread.sleep(100)}
            instrument.runOnMainSync {
                assertFalse("TIF session must connect from application UID",activity.connectionFailed)
                assertNotNull("Input service must report actual video state: ${input.id}",activity.videoState)
            }
        }finally{instrument.runOnMainSync{activity.finish()}}
        instrument.waitForIdleSync()
        }
    }
}
