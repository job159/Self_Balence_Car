package com.b570.pilot.ui.screens.tune

import com.b570.pilot.protocol.Params
import org.junit.Assert.assertTrue
import org.junit.Test

/** 內建設定檔要跟著主控板預設值走：「穩定」比原廠柔和、「運動」比原廠靈敏（主控板 v2 起 ACCEL 2000、TURN_ACCEL 12000）。 */
class TunePresetsTest {

    private fun def(id: Int): Float = Params.byId(id)!!.default

    @Test fun sportIsQuickerThanFactoryAndStableIsGentler() {
        for (id in listOf(Params.MAX_SPEED, Params.ACCEL, Params.TURN_ACCEL)) {
            val sport = TunePresets.SPORT.values.getValue(id)
            val stable = TunePresets.STABLE.values.getValue(id)
            assertTrue("SPORT ${Params.byId(id)!!.key}=$sport > default ${def(id)}", sport > def(id))
            assertTrue("STABLE ${Params.byId(id)!!.key}=$stable < default ${def(id)}", stable < def(id))
        }
    }

    @Test fun builtInValuesAreWithinFirmwareRange() {
        for (p in TunePresets.ALL) {
            for ((id, v) in p.values) {
                val d = Params.byId(id)!!
                assertTrue("${p.name} ${d.key}=$v", v >= d.min && v <= d.max)
            }
        }
    }
}
