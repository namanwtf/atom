package atom.xposed.hook

import android.os.Build
import io.github.libxposed.api.XposedInterface

/**
 * Google app eligibility for Circle to Search (Google app process only). Circle to Search is gated on the device
 * looking like a supported phone, so inside the Google app the device reads as a Pixel and reports the Pixel
 * experience features. Nothing outside the Google app's own processes sees this. Always on while the module is
 * enabled for the Google app (the scope is the switch).
 */
internal object GoogleEligibility {
    private val features = setOf(
        "com.google.android.feature.PIXEL_EXPERIENCE",
        "com.google.android.feature.PIXEL_2023_EXPERIENCE",
        "com.google.android.feature.PIXEL_2024_EXPERIENCE",
        "com.google.android.feature.PIXEL_2025_EXPERIENCE",
        "com.google.android.feature.PIXEL_2026_EXPERIENCE",
        "com.google.android.feature.GOOGLE_EXPERIENCE",
        "com.google.android.feature.CONTEXTUAL_SEARCH",
    )

    private val build = mapOf(
        "MANUFACTURER" to "Google",
        "BRAND" to "google",
        "MODEL" to "Pixel 9 Pro",
        "DEVICE" to "caiman",
        "PRODUCT" to "caiman",
    )

    fun install(env: HookEnv, loader: ClassLoader) {
        env.guard("gsa-build") {
            for ((name, value) in build) {
                val f = Build::class.java.getDeclaredField(name)
                f.isAccessible = true
                f.set(null, value)
            }
        }
        env.guard("gsa-features") {
            val pm = Class.forName("android.app.ApplicationPackageManager", false, loader)
            val n = env.hookDeclared(pm, "hasSystemFeature", "gsa-feature", FeatureHooker())
            env.logOnce("gsa-installed", "Google app eligibility hooks: $n")
        }
    }

    private class FeatureHooker : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val name = chain.args.firstOrNull() as? String
            return if (name != null && name in features) true else chain.proceed()
        }
    }
}
