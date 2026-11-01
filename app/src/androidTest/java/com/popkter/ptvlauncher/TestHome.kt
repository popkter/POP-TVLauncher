package com.popkter.ptvlauncher

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/** singleTask HOME may reuse an Activity; startActivitySync waits only for a new instance. */
internal fun launchTestHome():MainActivity {
    val instrument=InstrumentationRegistry.getInstrumentation()
    instrument.targetContext.startActivity(Intent(instrument.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    repeat(100) {
        var activity:MainActivity?=null
        instrument.runOnMainSync {
            activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
        }
        activity?.let {return it}
        Thread.sleep(100)
    }
    error("PTV did not resume")
}
