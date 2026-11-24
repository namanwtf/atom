package atom.voice

import atom.voice.policy.DeviceConditions
import atom.voice.policy.ListenDecision
import atom.voice.policy.ListeningPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class ListeningPolicyTest {
    private val ok = DeviceConditions(
        screenOn = true, charging = false, powerSave = false, thermalStatus = 0,
        batteryPercent = 80, inCall = false, micBusy = false,
    )
    private val default = WakePolicy()

    private fun decide(c: DeviceConditions, p: WakePolicy = default) = ListeningPolicy.decide(c, p)
    private fun pause(reason: String) = ListenDecision.Pause(reason)

    @Test
    fun screenOnListens() = assertEquals(ListenDecision.Listen, decide(ok))

    @Test
    fun screenOffOnBatteryPausesByDefault() =
        assertEquals(pause(ListeningPolicy.REASON_SCREEN_OFF), decide(ok.copy(screenOn = false)))

    @Test
    fun screenOffWhileChargingListensUnlessDisabled() {
        val c = ok.copy(screenOn = false, charging = true)
        assertEquals(ListenDecision.Listen, decide(c))
        assertEquals(pause(ListeningPolicy.REASON_SCREEN_OFF), decide(c, WakePolicy(listenWhileCharging = false)))
    }

    @Test
    fun screenOffListeningOptIn() =
        assertEquals(ListenDecision.Listen, decide(ok.copy(screenOn = false), WakePolicy(screenOffListening = true)))

    @Test
    fun batterySaverPauses() {
        assertEquals(pause(ListeningPolicy.REASON_POWER_SAVE), decide(ok.copy(powerSave = true)))
        assertEquals(pause(ListeningPolicy.REASON_POWER_SAVE), decide(ok.copy(powerSave = true, charging = true)))
    }

    @Test
    fun thermalSevereOrWorsePauses() {
        assertEquals(ListenDecision.Listen, decide(ok.copy(thermalStatus = 2))) // MODERATE
        for (s in 3..6) assertEquals(pause(ListeningPolicy.REASON_THERMAL), decide(ok.copy(thermalStatus = s)))
    }

    @Test
    fun lowBatteryPausesOnlyWhenNotCharging() {
        assertEquals(pause(ListeningPolicy.REASON_LOW_BATTERY), decide(ok.copy(batteryPercent = 19)))
        assertEquals(ListenDecision.Listen, decide(ok.copy(batteryPercent = 20)))
        assertEquals(ListenDecision.Listen, decide(ok.copy(batteryPercent = 5, charging = true)))
        assertEquals(ListenDecision.Listen, decide(ok.copy(batteryPercent = -1))) // unknown
    }

    @Test
    fun callAndBusyMicPause() {
        assertEquals(pause(ListeningPolicy.REASON_CALL), decide(ok.copy(inCall = true)))
        assertEquals(pause(ListeningPolicy.REASON_MIC_BUSY), decide(ok.copy(micBusy = true)))
    }

    @Test
    fun mostImportantReasonWins() {
        val everything = DeviceConditions(
            screenOn = false, charging = false, powerSave = true, thermalStatus = 4,
            batteryPercent = 3, inCall = true, micBusy = true,
        )
        assertEquals(pause(ListeningPolicy.REASON_CALL), decide(everything))
        assertEquals(pause(ListeningPolicy.REASON_MIC_BUSY), decide(everything.copy(inCall = false)))
        assertEquals(pause(ListeningPolicy.REASON_POWER_SAVE), decide(everything.copy(inCall = false, micBusy = false)))
        assertEquals(
            pause(ListeningPolicy.REASON_THERMAL),
            decide(everything.copy(inCall = false, micBusy = false, powerSave = false)),
        )
        assertEquals(
            pause(ListeningPolicy.REASON_LOW_BATTERY),
            decide(everything.copy(inCall = false, micBusy = false, powerSave = false, thermalStatus = 0)),
        )
    }
}
