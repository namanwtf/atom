@file:OptIn(ExperimentalMaterial3Api::class)

package atom.app.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import atom.app.R
import atom.app.data.EngineSelection
import atom.app.data.KeyVault
import atom.app.data.LocalBackend
import atom.app.data.ProviderStore
import atom.app.data.vaultDataStore
import atom.llm.cloud.AuthMethod
import atom.llm.cloud.ModelConfig
import atom.llm.cloud.ProviderConfig
import atom.models.ModelStore
import atom.ui.components.SecondaryButton
import atom.ui.theme.extras

private const val ALL = "\u0000all"
private const val ON_DEVICE = "\u0000local"

/**
 * "Choose model": every enabled provider's models plus installed on-device models, filterable by provider
 * and searchable. Self-contained, so any screen can show it for a per-conversation or per-role choice.
 */
@Composable
fun ModelPickerSheet(
    selected: EngineSelection?,
    onSelect: (EngineSelection) -> Unit,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    cloudOnly: Boolean = false,
) {
    val context = LocalContext.current
    val providerStore = remember { ProviderStore.get(context) }
    val vault = remember { KeyVault(context.applicationContext.vaultDataStore) }
    val localStore = remember { ModelStore.get(context) }
    val providers by providerStore.providers.collectAsState()
    val saved by vault.savedProviders.collectAsState(initial = emptySet())
    val signedIn by vault.signedInProviders.collectAsState(initial = emptySet())
    val installed by localStore.installed.collectAsState()

    var filter by rememberSaveable { mutableStateOf(ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val enabled = providers.filter { it.enabled && it.models.isNotEmpty() }
    val ready: (ProviderConfig) -> Boolean = { it.keyOptional || it.id in saved || (it.auth != AuthMethod.ApiKey && it.id in signedIn) }
    val ordered = enabled.sortedByDescending(ready)
    val q = query.trim().lowercase()
    fun matches(m: ModelConfig, p: ProviderConfig) = q.isEmpty() || m.id.lowercase().contains(q) ||
        m.displayName.lowercase().contains(q) || p.displayName.lowercase().contains(q)

    val groups = ordered.filter { filter == ALL || filter == it.id }
        .map { p -> p to p.models.filter { matches(it, p) } }
        .filter { it.second.isNotEmpty() }
    val locals = if (cloudOnly || (filter != ALL && filter != ON_DEVICE)) emptyList() else installed.filter { q.isEmpty() || it.name.lowercase().contains(q) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.outline))
        },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding()) {
            Text(
                stringResource(R.string.mdl_choose_model),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SearchField(query, { query = it }, stringResource(R.string.mdl_search_models), Modifier.padding(horizontal = 16.dp))
            val chips = buildList {
                add(ALL to stringResource(R.string.mdl_filter_all))
                ordered.forEach { add(it.id to it.displayName) }
                if (!cloudOnly && installed.isNotEmpty()) add(ON_DEVICE to stringResource(R.string.tab_on_device))
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chips, key = { it.first }) { (id, label) ->
                    FilterChip(label, selected = filter == id) { filter = id }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                if (groups.isEmpty() && locals.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.mdl_no_match),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                for ((provider, models) in groups) {
                    item(key = "h-${provider.id}") {
                        Row(Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(provider.displayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!ready(provider)) {
                                Spacer(Modifier.width(8.dp))
                                QuietPill(stringResource(if (provider.auth == AuthMethod.ApiKey) R.string.mdl_status_no_key else R.string.mdl_status_signed_out))
                            }
                        }
                    }
                    items(models, key = { "m-${provider.id}-${it.id}" }) { model ->
                        val selection = EngineSelection.Cloud(provider.id, model.id)
                        PickerRow(
                            title = model.displayName,
                            subtitle = listOfNotNull(
                                model.id.takeIf { it != model.displayName },
                                model.effectiveContextWindow?.let(::formatTokens),
                                if (model.reasoning.supported) stringResource(R.string.mdl_badge_reasoning) else null,
                            ).joinToString(" · ").ifEmpty { null },
                            checked = selected == selection || (selected is EngineSelection.Cloud && selected.providerId == provider.id && selected.model == model.id),
                            onClick = { onSelect(selection) },
                        )
                    }
                }
                if (locals.isNotEmpty()) {
                    item(key = "h-local") {
                        Text(
                            stringResource(R.string.mdl_on_this_phone),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp),
                        )
                    }
                    items(locals, key = { "l-${it.id}" }) { model ->
                        val current = (selected as? EngineSelection.Local)?.takeIf { it.installedModelId == model.id }
                        PickerRow(
                            title = model.name,
                            subtitle = stringResource(R.string.badge_on_device),
                            checked = current != null,
                            onClick = { onSelect(EngineSelection.Local(model.id, current?.backend ?: LocalBackend.Auto)) },
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                SecondaryButton(stringResource(R.string.mdl_manage_models), onClick = onManage)
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.primary else c.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) c.onPrimary else c.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String?, checked: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (checked) c.surfaceContainerHigh else c.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.extras.mono.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        CheckMark(checked)
    }
}
