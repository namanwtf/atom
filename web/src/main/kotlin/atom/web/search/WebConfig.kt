package atom.web.search

import android.content.Context
import atom.web.JsonPref
import kotlinx.serialization.Serializable

@Serializable
enum class ServiceKind { BuiltIn, DeepSeek, Custom }

@Serializable
data class HeaderEntry(val name: String = "", val value: String = "")

/** A user-defined HTTP endpoint for search ({query}, {count}) or page reading ({url}). */
@Serializable
data class CustomEndpoint(
    val method: String = "GET",
    val url: String = "",
    val bodyTemplate: String = "",
    /** Search: path to the results array. Reading: path to the object holding the page (blank = root). */
    val resultsPath: String = "",
    val titlePath: String = "title",
    val linkPath: String = "url",
    val snippetPath: String = "snippet",
    val bodyPath: String = "content",
    val headers: List<HeaderEntry> = emptyList(),
) {
    val configured: Boolean get() = url.startsWith("http://") || url.startsWith("https://")
}

@Serializable
data class WebConfig(
    val enabled: Boolean = true,
    val search: ServiceKind = ServiceKind.BuiltIn,
    val read: ServiceKind = ServiceKind.BuiltIn,
    /** Blank uses the matched DeepSeek provider's URL, else https://api.deepseek.com. */
    val deepSeekBaseUrl: String = "",
    val searchEndpoint: CustomEndpoint = CustomEndpoint(resultsPath = "results"),
    val readEndpoint: CustomEndpoint = CustomEndpoint(bodyPath = "content"),
    val browserEnabled: Boolean = true,
)

object WebSecrets {
    const val DEEPSEEK_KEY = "web.deepseek.key"
    const val SEARCH_KEY = "web.search.custom.key"
    const val READ_KEY = "web.read.custom.key"
}

internal object WebSettings {
    @Volatile
    private var pref: JsonPref<WebConfig>? = null

    fun of(context: Context): JsonPref<WebConfig> =
        pref ?: synchronized(this) {
            pref ?: JsonPref(context, "web_config", WebConfig.serializer(), WebConfig()).also { pref = it }
        }
}
