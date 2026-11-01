// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import org.junit.Assert.*
import org.junit.Test

class LauncherPolicyTest {
    @Test fun pinOrderRoundTripKeepsOrderAndRemovesDuplicates() {
        assertEquals(listOf("video", "music", "files"), PinOrder.decode(PinOrder.encode(listOf("video", "music", "video", "files"))))
        assertEquals(emptyList<String>(), PinOrder.decode(""))
    }
    @Test fun missingApplicationsDoNotProduceDeadTiles() {
        assertEquals(listOf("music"), PinOrder.visible(listOf("removed", "music", "music"), setOf("music", "files")))
    }
    @Test fun desktopLimitStillAllowsRemovingAndReplacingPins() {
        val full = (1..10).map { "app$it" }
        assertEquals(full, PinOrder.toggle(full, "extra"))
        val removed = PinOrder.toggle(full, "app5")
        assertEquals(9, removed.size)
        assertEquals(10, PinOrder.toggle(removed, "extra").size)
        assertTrue("extra" in PinOrder.toggle(removed, "extra"))
    }
    @Test fun movingAtBoundariesIsStable() {
        val list = listOf("video", "music", "files")
        assertEquals(list, PinOrder.move(list, "video", -1))
        assertEquals(list, PinOrder.move(list, "files", 1))
        assertEquals(listOf("music", "video", "files"), PinOrder.move(list, "video", 1))
        assertEquals(list, PinOrder.move(list, "missing", -1))
    }
    @Test fun shortBackProducesExactlyOneReturn() {
        val gate = BackPressGate()
        gate.down(100)
        assertFalse(gate.longPress(699))
        assertTrue(gate.up(false))
        assertFalse(gate.up(false))
    }
    @Test fun heldBackOpensOnceAndDoesNotReturn() {
        val gate = BackPressGate()
        gate.down(100)
        gate.down(400) // Hardware repeats must not reset the first down time.
        assertTrue(gate.longPress(700))
        assertFalse(gate.longPress(1200))
        assertFalse(gate.up(false))
    }
    @Test fun cancelledAndInterruptedKeysNeverNavigate() {
        val gate = BackPressGate()
        gate.down(100); assertFalse(gate.up(true))
        gate.down(100); gate.reset()
        assertFalse(gate.longPress(1000)); assertFalse(gate.up(false))
    }
    @Test fun saverOnlyStartsOnIdleHomeWithoutPanels() {
        assertFalse(IdlePolicy.shouldShow(false, false, 181000, 1000, 3))
        assertFalse(IdlePolicy.shouldShow(true, true, 181000, 1000, 3))
        assertFalse(IdlePolicy.shouldShow(true, false, 180999, 1000, 3))
        assertTrue(IdlePolicy.shouldShow(true, false, 181000, 1000, 3))
        assertFalse(IdlePolicy.shouldShow(true, false, 181000, 1000, 0))
    }
    @Test fun duplicateHomeWindowsDoNotRepeatedlyLaunchTheActivity() {
        val gate=HomeReturnGate()
        assertTrue(gate.claim(100))
        assertFalse(gate.claim(101))
        assertFalse(gate.claim(1599))
        assertTrue(gate.claim(1600))
    }
    @Test fun failedOrFinishedHomeLaunchCanBeRetriedImmediately() {
        val gate=HomeReturnGate()
        assertTrue(gate.claim(100))
        gate.reset()
        assertTrue(gate.claim(110))
    }
}
