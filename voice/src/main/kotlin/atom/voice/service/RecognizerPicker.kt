package atom.voice.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.speech.RecognitionService

/** Finds a real speech recognizer that is not Atom's own pass-through ([atom.voice.assist.AtomRecognitionService]). */
internal object RecognizerPicker {
    fun pick(context: Context): ComponentName? {
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName && it.exported }
        val best = services.firstOrNull { (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
            ?: services.firstOrNull()
        return best?.let { ComponentName(it.packageName, it.name) }
    }
}
