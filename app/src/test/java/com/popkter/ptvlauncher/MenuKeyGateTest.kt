// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test
import com.popkter.ptvlauncher.MenuKeyGate.Result.*

class MenuKeyGateTest {
    @Test fun shortPressOpensOnDownAndConsumesRelease() {
        val gate=MenuKeyGate()
        assertEquals(OPEN,gate.down(100,0,true))
        assertEquals(CONSUME,gate.up(100))
        assertEquals(PASS,gate.up(100))
    }
    @Test fun holdingAndDuplicateDownNeverOpenTwice() {
        val gate=MenuKeyGate()
        assertEquals(OPEN,gate.down(100,0,true))
        assertEquals(CONSUME,gate.down(100,0,true))
        assertEquals(CONSUME,gate.down(100,1,false)) // Native menu now has focus.
        assertEquals(CONSUME,gate.down(100,2,false))
        assertEquals(CONSUME,gate.up(100))
        assertEquals(PASS,gate.down(200,0,false))
    }
    @Test fun otherAppsKeepTheirWholeMenuSequence() {
        val gate=MenuKeyGate()
        assertEquals(PASS,gate.down(100,0,false))
        assertEquals(PASS,gate.down(100,1,true)) // Focus change cannot claim half a gesture.
        assertEquals(PASS,gate.up(100))
    }
    @Test fun newPressRecoversWhenFirmwareDropsRelease() {
        val gate=MenuKeyGate()
        assertEquals(OPEN,gate.down(100,0,true))
        assertEquals(OPEN,gate.down(200,0,true))
        assertEquals(PASS,gate.up(100))
        assertEquals(CONSUME,gate.up(200))
    }
    @Test fun interruptionClearsOldGesture() {
        val gate=MenuKeyGate()
        gate.down(100,0,true);gate.reset()
        assertEquals(PASS,gate.up(100))
        assertEquals(OPEN,gate.down(200,0,true))
    }
}
