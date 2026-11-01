// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LauncherDeviceTest {
    @Test fun preferencesSurviveClosingAndReopeningStore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "persistence-test.preferences_pb")
        file.delete()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = LauncherStore(context, firstScope, PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }))
        try {
            first.initialize(listOf("video", "music"))
            first.move("music", -1)
            first.idle(5)
            withTimeout(5000) { first.state.first { it.pins == listOf("music", "video") && it.idleMinutes == 5 } }
        } finally { firstScope.cancel(); firstScope.coroutineContext[Job]?.join() }
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val second = LauncherStore(context, secondScope, PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { file }))
            val restored = withTimeout(5000) { second.state.first { it.initialized } }
            assertEquals(listOf("music", "video"), restored.pins)
            assertEquals(5, restored.idleMinutes)
            second.initialize(listOf("replacement"))
            assertEquals(listOf("music", "video"), second.state.value.pins)
        } finally { secondScope.cancel(); secondScope.coroutineContext[Job]?.join(); file.delete() }
    }
    @Test fun applicationEnumerationIsUniqueAndLaunchableOnThisTv() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val repo = withContext(Dispatchers.Main) { AppRepository(context, scope) }
            val apps = withTimeout(15000) { repo.scannedEntries.filterNotNull().first { it.isNotEmpty() } }
            assertEquals(apps.size, apps.map { it.id }.toSet().size)
            assertFalse(apps.any { it.id == context.packageName })
            assertTrue(apps.any { it.launchable })
            apps.filter { it.launchable }.forEach {
                assertNotNull(repo.launchIntent(it.id))
            }
        } finally { scope.cancel() }
    }
}
