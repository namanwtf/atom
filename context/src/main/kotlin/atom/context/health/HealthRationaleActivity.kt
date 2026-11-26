package atom.context.health

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Shown by Health Connect when the user asks why Atom wants health data. Required for the permission request. */
class HealthRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(pad, pad * 2, pad, pad)
            text = "How Atom uses your health data\n\n" +
                "Atom reads steps, distance, calories, sleep, exercise, heart rate and weight from Health Connect " +
                "only when you ask it a question that needs them, for example \"how did I sleep this week?\", and asks for each kind of data the first time a question needs it.\n\n" +
                "Atom never writes health data, never stores a copy, and never syncs it anywhere. The numbers are sent only " +
                "to the AI model you chose in Atom, for that one answer.\n\n" +
                "You can turn any data type off at any time in Health Connect."
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(text) })
    }
}
