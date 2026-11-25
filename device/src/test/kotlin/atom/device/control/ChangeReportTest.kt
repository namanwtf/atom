package atom.device.control

import kotlin.test.Test
import kotlin.test.assertEquals

class ChangeReportTest {
    @Test
    fun verifiedChangeShowsBeforeAndAfter() {
        assertEquals("Auto-rotate: off → on (verified)", Change("Auto-rotate", "off", "on", "on", ChangeStatus.Verified).text)
    }

    @Test
    fun verifiedChangeNamesTheRoute() {
        assertEquals("Wi-Fi: off → on (verified via Shizuku)", Change("Wi-Fi", "off", "on", "on", ChangeStatus.Verified, via = "Shizuku").text)
    }

    @Test
    fun unchangedIsNotDescribedAsAChange() {
        assertEquals("Wi-Fi: already on; nothing changed", Change("Wi-Fi", "on", "on", "on", ChangeStatus.Unchanged).text)
    }

    @Test
    fun unconfirmedWhenStillOldValue() {
        assertEquals(
            "Bluetooth: off → on requested, unconfirmed (it still reads off)",
            Change("Bluetooth", "off", "on", "off", ChangeStatus.Unconfirmed).text,
        )
    }

    @Test
    fun unconfirmedWhenUnreadable() {
        assertEquals(
            "NFC: on → off requested, unconfirmed (the new state could not be read back)",
            Change("NFC", "on", "off", null, ChangeStatus.Unconfirmed).text,
        )
    }

    @Test
    fun unconfirmedWithDifferentReading() {
        assertEquals(
            "Brightness: 40% → 70% requested, unconfirmed (it now reads 65%)",
            Change("Brightness", "40%", "70%", "65%", ChangeStatus.Unconfirmed).text,
        )
    }

    @Test
    fun failedKeepsOldValueAndReason() {
        assertEquals(
            "Mobile data: still on; could not set off. Needs Shizuku or root",
            Change("Mobile data", "on", "off", "on", ChangeStatus.Failed, note = "Needs Shizuku or root").text,
        )
    }

    @Test
    fun unknownBeforeIsSaid() {
        assertEquals("Flashlight: unknown → on (verified)", Change("Flashlight", null, "on", "on", ChangeStatus.Verified).text)
    }

    @Test
    fun statusIsVerifiedOnlyWhenTargetReadBack() {
        assertEquals(ChangeStatus.Verified, ChangeReport.status("off", "on", "on"))
        assertEquals(ChangeStatus.Unconfirmed, ChangeReport.status("off", "on", "off"))
        assertEquals(ChangeStatus.Unconfirmed, ChangeReport.status("off", "on", null))
    }
}
