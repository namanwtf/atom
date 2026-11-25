package atom.device.godmode

import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryDrainTest {
    @Test
    fun checkinRowsAggregatePerUidAndSort() {
        val out = """
            9,10234,l,pwi,uid,120.5,0,0,0
            9,10500,l,pwi,uid,30.0,0,0,0
            9,0,l,pwi,scrn,200.25,0,0,0
            9,10234,l,pwi,uid,4.5,0,0,0
            9,10234,u,pwi,uid,999,0,0,0
            9,1000,l,pwi,uid,0,0,0,0
        """.trimIndent()
        val items = BatteryDrain.parseCheckin(out)
        assertEquals(listOf("Screen", "uid 10234", "uid 10500"), items.map { it.label })
        assertEquals(125.0, items[1].mah)
    }

    @Test
    fun textSectionUidFormats() {
        val out = """
            Capacity: 5000, Computed drain: 1234, actual drain: 1100-1300
            Screen: 210
            UID u0a234: 88.4 ( cpu=40.1 wifi=2.0 )
            UID u0s1000: 20.0 ( cpu=20.0 )
            UID 1041: 3.1
        """.trimIndent()
        val items = BatteryDrain.parseText(out)
        assertEquals(DrainItem(null, "Screen", 210.0), items[0])
        assertEquals(10_234, items[1].uid)
        assertEquals(1000, items[2].uid)
        assertEquals(1041, items[3].uid)
    }
}
