// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

/** Claim a HOME menu gesture on its initial down, even if focus changes before release. */
internal class MenuKeyGate {
    enum class Result { PASS, CONSUME, OPEN }
    private var claimed:Long?=null
    fun down(time:Long,repeat:Int,onHome:Boolean):Result {
        if(claimed==time)return Result.CONSUME
        if(repeat>0)return Result.PASS
        claimed=if(onHome)time else null
        return if(onHome)Result.OPEN else Result.PASS
    }
    fun up(time:Long):Result {
        if(claimed!=time)return Result.PASS
        claimed=null
        return Result.CONSUME
    }
    fun reset() {claimed=null}
}
