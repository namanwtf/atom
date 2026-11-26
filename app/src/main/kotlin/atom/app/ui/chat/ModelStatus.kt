package atom.app.ui.chat

import android.content.Context
import atom.app.R

/**
 * What every screen says about the current model: the home capsule, the drawer's Models row, Settings' Model
 * providers row and the floating assistant chip. One answer, built from the same readiness check that blocks
 * sending ([notReadyReason]), so no screen can call a model ready while another says it is not set.
 */
sealed interface ModelStatus {
    /** Usable: sends go out. */
    data class Ready(val name: String) : ModelStatus

    /** Selected, but its provider has no API key yet (for example DeepSeek on a fresh install). */
    data class NeedsKey(val name: String) : ModelStatus

    /** An account provider that is not signed in. */
    data class SignIn(val provider: String) : ModelStatus

    /** The saved key could not be decrypted on this phone. */
    data class KeyUnreadable(val name: String) : ModelStatus

    /** An on-device model without the on-device AI pack. */
    data class NeedsPack(val name: String) : ModelStatus

    /** Nothing usable is chosen (no selection, model removed, provider off). */
    data object NotSet : ModelStatus

    val ready: Boolean get() = this is Ready

    companion object {
        /** [name] is the selection's display name (null when there is no selection); [reason] null means usable. */
        fun of(name: String?, reason: NotReady?, providerName: String?): ModelStatus = when {
            name.isNullOrBlank() && reason == NotReady.SignIn && providerName != null -> SignIn(providerName)
            name.isNullOrBlank() -> NotSet
            reason == null -> Ready(name)
            reason == NotReady.NeedsKey -> NeedsKey(name)
            reason == NotReady.SignIn -> if (providerName != null) SignIn(providerName) else NotSet
            reason == NotReady.KeyUnreadable -> KeyUnreadable(name)
            reason == NotReady.NeedsPack -> NeedsPack(name)
            else -> NotSet
        }

        /**
         * True when a key-based provider is otherwise fine and only its key is missing: that is "add key", not "not
         * set". Providers whose key is optional (local servers, custom endpoints) never need one.
         */
        fun keyMissing(providerEnabled: Boolean, apiKeyAuth: Boolean, keyOptional: Boolean, hasKey: Boolean, modelExists: Boolean): Boolean =
            providerEnabled && apiKeyAuth && !keyOptional && !hasKey && modelExists
    }
}

/** The one label for [status], the same on every screen. */
fun ModelStatus.label(context: Context): String = when (this) {
    is ModelStatus.Ready -> name
    is ModelStatus.NeedsKey -> context.getString(R.string.home_model_needs_key, name)
    is ModelStatus.SignIn -> context.getString(R.string.home_sign_in_capsule, provider)
    is ModelStatus.KeyUnreadable -> context.getString(R.string.home_model_key_unreadable, name)
    is ModelStatus.NeedsPack -> context.getString(R.string.home_model_needs_pack, name)
    ModelStatus.NotSet -> context.getString(R.string.home_not_set)
}
