package atom.web.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import atom.ui.components.AtomCard
import atom.ui.components.AtomSwitch
import atom.ui.components.BottomActionBar
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.PrimaryButton
import atom.ui.components.SectionLabel
import atom.ui.feature.FeatureNav
import atom.web.Web
import atom.web.WebFeature
import atom.web.search.CustomEndpoint
import atom.web.search.HeaderEntry
import atom.web.search.ServiceKind
import atom.web.search.WebConfig
import atom.web.search.WebSecrets
import atom.web.search.WebServices
import atom.web.search.WebSettings
import kotlinx.coroutines.launch

private fun ServiceKind.label() = when (this) {
    ServiceKind.BuiltIn -> "Built-in default"
    ServiceKind.DeepSeek -> "DeepSeek"
    ServiceKind.Custom -> "Custom endpoint"
}

/** Editable copy of one secret: "" means unchanged; [removed] clears the stored value on save. */
private class SecretDraft(val name: String) {
    var saved by mutableStateOf(false)
    var unreadable by mutableStateOf(false)
    var value by mutableStateOf("")
    var removed by mutableStateOf(false)
}

@Composable
internal fun WebSearchSettingsScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val pref = remember { WebSettings.of(context) }
    var draft by remember { mutableStateOf(pref.value) }
    val secrets = remember { listOf(WebSecrets.DEEPSEEK_KEY, WebSecrets.SEARCH_KEY, WebSecrets.READ_KEY).associateWith { SecretDraft(it) } }
    var deepSeekProvider by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val host = Web.host
        secrets.values.forEach { s ->
            s.unreadable = runCatching { host.secrets.isUnreadable(s.name) }.getOrDefault(false)
            s.saved = s.unreadable || runCatching { !host.secrets.get(s.name).isNullOrBlank() }.getOrDefault(false)
        }
        deepSeekProvider = runCatching { host.providerKeys.all().firstOrNull { WebServices.host(it.baseUrl).endsWith("deepseek.com") }?.displayName }.getOrNull()
    }

    suspend fun save(): Boolean {
        val store = Web.host.secrets
        return runCatching {
            secrets.values.forEach { s ->
                when {
                    s.removed -> store.put(s.name, null)
                    s.value.isNotBlank() -> store.put(s.name, s.value.trim())
                }
                if (s.removed) s.saved = false
                if (s.value.isNotBlank()) { s.saved = true; s.unreadable = false }
                s.value = ""
                s.removed = false
            }
            pref.set(draft)
            WebFeature.services(context).clearCache()
            true
        }.getOrElse { status = "Could not save: ${it.message}"; false }
    }

    LargeTitleScreen(
        title = "Web search and reading",
        onBack = nav::back,
        bottomBar = {
            BottomActionBar {
                PrimaryButton("Save", onClick = { scope.launch { if (save()) status = "Saved." } })
            }
        },
    ) {
        item {
            Group {
                GroupRow("Enable web search, reading and browser", subtitle = "Lets the agent search and read the web", trailing = {
                    AtomSwitch(draft.enabled, { draft = draft.copy(enabled = it) })
                })
                GroupDivider(18.dp)
                GroupRow("Agent browser", subtitle = "Off-screen pages per conversation for logins, clicks and forms", enabled = draft.enabled, trailing = {
                    AtomSwitch(draft.browserEnabled, { draft = draft.copy(browserEnabled = it) }, enabled = draft.enabled)
                })
            }
        }
        item { SectionLabel("Search service") }
        item {
            Group {
                ServiceKind.entries.forEachIndexed { i, k ->
                    if (i > 0) GroupDivider(18.dp)
                    ChoiceRow(k.label(), draft.search == k, { draft = draft.copy(search = k) }, subtitle = serviceHint(k, true))
                }
            }
        }
        item { SectionLabel("Page-reading service") }
        item {
            Group {
                ServiceKind.entries.forEachIndexed { i, k ->
                    if (i > 0) GroupDivider(18.dp)
                    ChoiceRow(k.label(), draft.read == k, { draft = draft.copy(read = k) }, subtitle = serviceHint(k, false))
                }
            }
        }
        if (draft.search == ServiceKind.DeepSeek || draft.read == ServiceKind.DeepSeek) {
            item { SectionLabel("DeepSeek") }
            item {
                Group {
                    GroupRow(
                        "Credential",
                        subtitle = deepSeekProvider?.let { "Uses the key of the \"$it\" provider unless you enter one below" }
                            ?: "No DeepSeek provider found; enter a key below",
                    )
                    GroupDivider(18.dp)
                    SecretField("Override key (optional)", secrets.getValue(WebSecrets.DEEPSEEK_KEY))
                    GroupDivider(18.dp)
                    FieldRow("Base URL (optional)", draft.deepSeekBaseUrl, { draft = draft.copy(deepSeekBaseUrl = it) }, placeholder = "https://api.deepseek.com", keyboard = KeyboardType.Uri)
                }
            }
        }
        if (draft.search == ServiceKind.Custom) {
            item { SectionLabel("Custom search endpoint") }
            item {
                EndpointEditor(draft.searchEndpoint, secrets.getValue(WebSecrets.SEARCH_KEY), search = true) { draft = draft.copy(searchEndpoint = it) }
            }
        }
        if (draft.read == ServiceKind.Custom) {
            item { SectionLabel("Custom reading endpoint") }
            item {
                EndpointEditor(draft.readEndpoint, secrets.getValue(WebSecrets.READ_KEY), search = false) { draft = draft.copy(readEndpoint = it) }
            }
        }
        item {
            Spacer(Modifier.height(18.dp))
            AtomCard {
                Text("Test", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Saves, then runs a search for \"Android\" and reads the first result.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompactButton(if (testing) "Testing…" else "Test search and reading", enabled = !testing, onClick = {
                        scope.launch {
                            if (!save()) return@launch
                            testing = true
                            status = runCatching {
                                val s = WebFeature.services(context)
                                val r = s.search("Android", 5)
                                val first = r.results.firstOrNull()
                                val page = first?.let { runCatching { s.read(it.url) }.getOrNull() }
                                buildString {
                                    append("Search (${r.source}): ${r.results.size} results.")
                                    r.note?.let { append(" ").append(it) }
                                    if (first != null) append("\nReading (${page?.source ?: "failed"}): ${page?.text?.length ?: 0} characters from ${first.url}")
                                }
                            }.getOrElse { "Failed: ${it.message}" }
                            testing = false
                        }
                    })
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item {
            Text(
                atom.web.search.KeylessSources.CONTACTED + " It needs no key but can be rate-limited; " +
                    "DeepSeek or a custom endpoint is more reliable. Keys are stored encrypted on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            )
        }
    }
}

private fun serviceHint(k: ServiceKind, search: Boolean) = when (k) {
    ServiceKind.BuiltIn -> if (search) "Public search, no key" else "Fetches the page on this phone"
    ServiceKind.DeepSeek -> if (search) "DeepSeek Search API (billed per request)" else "DeepSeek Fetch API; falls back to this phone"
    ServiceKind.Custom -> "Your own HTTP endpoint"
}

@Composable
private fun SecretField(label: String, s: SecretDraft) {
    FieldRow(
        label,
        s.value,
        { s.value = it; s.removed = false },
        placeholder = when {
            s.unreadable -> "Saved key can't be read; enter it again"
            s.saved && !s.removed -> "Saved"
            else -> "Not set"
        },
        secret = true,
    )
    if (s.saved && !s.removed) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, bottom = 12.dp)) {
            CompactButton("Remove saved key", onClick = { s.removed = true; s.value = "" }, primary = false)
        }
    }
}

@Composable
private fun EndpointEditor(ep: CustomEndpoint, key: SecretDraft, search: Boolean, onChange: (CustomEndpoint) -> Unit) {
    var headersText by remember { mutableStateOf(ep.headers.joinToString("\n") { "${it.name}: ${it.value}" }) }
    Group {
        PickerRow("Method", listOf("GET", "POST", "PUT"), ep.method.uppercase(), { it }, { onChange(ep.copy(method = it)) })
        GroupDivider(18.dp)
        FieldRow(
            "URL", ep.url, { onChange(ep.copy(url = it.trim())) },
            placeholder = if (search) "https://example.com/search?q={query}&n={count}" else "https://example.com/read?url={url}",
            keyboard = KeyboardType.Uri, mono = true,
        )
        GroupDivider(18.dp)
        SecretField("API key (sent as Bearer token, optional)", key)
        GroupDivider(18.dp)
        FieldRow(
            "Request body (JSON template)", ep.bodyTemplate, { onChange(ep.copy(bodyTemplate = it)) },
            placeholder = if (search) "{\"query\": \"{query}\", \"count\": {count}}" else "{\"url\": \"{url}\"}",
            hint = "Placeholders: " + (if (search) "{query}, {count}" else "{url}") + ". Not sent with GET.",
            singleLine = false, mono = true,
        )
        GroupDivider(18.dp)
        FieldRow(
            if (search) "Results array path" else "Result object path (optional)", ep.resultsPath, { onChange(ep.copy(resultsPath = it.trim())) },
            placeholder = if (search) "data.results" else "data", mono = true,
            hint = "Dot path with [n] or [*], e.g. data.items or $.web.results",
        )
        GroupDivider(18.dp)
        FieldRow("Title field", ep.titlePath, { onChange(ep.copy(titlePath = it.trim())) }, placeholder = "title", mono = true)
        if (search) {
            GroupDivider(18.dp)
            FieldRow("Link field", ep.linkPath, { onChange(ep.copy(linkPath = it.trim())) }, placeholder = "url", mono = true)
            GroupDivider(18.dp)
            FieldRow("Snippet field", ep.snippetPath, { onChange(ep.copy(snippetPath = it.trim())) }, placeholder = "snippet", mono = true)
        } else {
            GroupDivider(18.dp)
            FieldRow("Body field", ep.bodyPath, { onChange(ep.copy(bodyPath = it.trim())) }, placeholder = "content", mono = true)
        }
        GroupDivider(18.dp)
        FieldRow(
            "Custom headers", headersText,
            { text ->
                headersText = text
                onChange(ep.copy(headers = text.lines().mapNotNull { l ->
                    val i = l.indexOf(':')
                    if (i <= 0) null else HeaderEntry(l.substring(0, i).trim(), l.substring(i + 1).trim())
                }))
            },
            placeholder = "X-Api-Version: 2", hint = "One per line, Name: value", singleLine = false, mono = true,
        )
    }
}
