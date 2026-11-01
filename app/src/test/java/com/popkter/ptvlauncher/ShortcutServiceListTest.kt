package com.popkter.ptvlauncher

import org.junit.Assert.*
import org.junit.Test

class ShortcutServiceListTest {
    private val own="com.popkter.ptvlauncher/.SwitcherService"
    @Test fun emptyOrFirmwareNullRestoresOnlyOwnService() {
        assertEquals(own,ShortcutServiceList.enabled(null,own))
        assertEquals(own,ShortcutServiceList.enabled("null",own))
    }
    @Test fun preservesOthersAndRecognizesBothComponentSpellings() {
        val others="com.example.voice/.Voice:com.example.reader/.Reader"
        val old="$others:com.popkter.ptvlauncher/com.popkter.ptvlauncher.SwitcherService"
        assertEquals("$others:$own",ShortcutServiceList.enabled(old,own))
        assertEquals(others,ShortcutServiceList.without("$others:$own",own).joinToString(":"))
    }
    @Test fun repeatedRecoveryIsIdempotent() {
        val restored=ShortcutServiceList.enabled("com.example.voice/.Voice:$own",own)
        assertEquals(restored,ShortcutServiceList.enabled(restored,own))
    }
}
