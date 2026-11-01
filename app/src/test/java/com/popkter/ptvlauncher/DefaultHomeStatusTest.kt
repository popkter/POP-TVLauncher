// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import org.junit.Assert.*
import org.junit.Test

class DefaultHomeStatusTest {
    @Test fun nativeDefaultDoesNotNeedAccessibility() {
        val status = DefaultHomeStatus(true, true, false)
        assertTrue(status.ready)
        assertFalse(status.needsCompatibility)
    }
    @Test fun xiaomiRoleSelectionRequiresCompatibilityWhenRoutingIsForced() {
        val selected = DefaultHomeStatus(true, false, false)
        assertTrue(selected.needsCompatibility)
        assertFalse(selected.ready)
        assertFalse(selected.copy(accessibilityEnabled = true).ready)
        assertTrue(selected.copy(accessibilityEnabled = true, accessibilityConnected = true).ready)
    }
    @Test fun accessibilityAloneDoesNotClaimDefaultHomeSelection() {
        val status = DefaultHomeStatus(false, false, true)
        assertFalse(status.selected)
        assertFalse(status.ready)
        assertFalse(status.needsCompatibility)
    }
}
