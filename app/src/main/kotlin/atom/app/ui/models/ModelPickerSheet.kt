package atom.app.ui.models

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import atom.app.R
import atom.app.data.EngineSelection
import atom.app.data.KeyVault
import atom.app.data.LocalBackend
import atom.app.data.ProviderStore
import atom.app.data.vaultDataStore
import atom.app.ui.common.ProviderMark
import atom.llm.cloud.AuthMethod
import atom.llm.cloud.ProviderConfig
import atom.models.ModelStore
import atom.ui.components.AtomSearchBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

private const val ALL = "\u0000all"
private const val ON_DEVICE = "\u0000local"

/** The sheet closes this long after a pick, so the check visibly moves first. */
private const val CLOSE_DELAY_MS = 140L

/** With this many choices or fewer, a small popup opens instead of the sheet. */
private const val POPUP_MAX = 8

/** One choice in the picker. */
private data class PickerOption(
    val key: String,
    val providerId: String?,
    val providerName: String,
    val title: String,
    val subtitle: String?,
    val selection: EngineSelection,
    val baseUrl: String? = null,
    val model: String? = null,
)

/**
 * "Choose model": every enabled provider's models plus installed on-device models, filterable by provider and
 * searchable, in a sheet 68% of the window tall. With [compact] and at most eight choices it is a small popup near
 * the top instead. [onFollow] adds a first "follow" choice (for per-job models). Self-contained, so any screen can
 * show it.
 */
@Composable
fun ModelPickerSheet(
    selected: EngineSelection?,
    onSelect: (EngineSelection) -> Unit,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    cloudOnly: Boolean = false,
    compact: Boolean = true,
    followLabel: String? = null,
    followHint: String? = null,
    onFollow: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providerStore = remember { ProviderStore.get(context) }
    val vault = remember { KeyVault(context.applicationContext.vaultDataStore) }
    val localStore = remember { ModelStore.get(context) }
    val providers by providerStore.providers.collectAsState()
    val saved by vault.savedProviders.collectAsState(initial = emptySet())
    val signedIn by vault.signedInProviders.collectAsState(initial = emptySet())
    val installed by localStore.installed.collectAsState()

    var filter by rememberSaveable { mutableStateOf(ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var show by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf<EngineSelection?>(null) }
    var following by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }

    val ready: (ProviderConfig) -> Boolean = { it.keyOptional || it.id in saved || (it.auth != AuthMethod.ApiKey && it.id in signedIn) }
    val ordered = providers.filter { it.enabled && it.models.isNotEmpty() }.sortedByDescending(ready)
    val onDeviceName = stringResource(R.string.mdl_on_this_phone)
    val onDeviceSub = stringResource(R.string.badge_on_device)
    val all = buildList {
        for (p in ordered) {
            for (m in p.models) {
                add(
                    PickerOption(
                        key = "m-${p.id}-${m.id}",
                        providerId = p.id,
                        providerName = p.displayName,
                        title = m.displayName,
                        subtitle = m.id.takeIf { it != m.displayName },
                        selection = EngineSelection.Cloud(p.id, m.id),
                        baseUrl = p.baseUrl,
                        model = m.id,
                    ),
                )
            }
        }
        if (!cloudOnly) {
            for (m in installed) {
                val current = (selected as? EngineSelection.Local)?.takeIf { it.installedModelId == m.id }
                add(
                    PickerOption(
                        key = "l-${m.id}",
                        providerId = null,
                        providerName = onDeviceName,
                        title = m.name,
                        subtitle = onDeviceSub,
                        selection = EngineSelection.Local(m.id, current?.backend ?: LocalBackend.Auto),
                    ),
                )
            }
        }
    }
    val chosen = picked ?: selected
    fun isSelected(o: PickerOption): Boolean = !following && when (val c = chosen) {
        is EngineSelection.Cloud -> o.selection is EngineSelection.Cloud && o.selection.providerId == c.providerId && o.selection.model == c.model
        is EngineSelection.Local -> o.selection is EngineSelection.Local && o.selection.installedModelId == c.installedModelId
        null -> false
    }

    fun pick(o: PickerOption) {
        if (picked != null) return
        picked = o.selection
        scope.launch {
            delay(CLOSE_DELAY_MS)
            show = false
        }
    }

    fun follow() {
        if (picked != null || following) return
        following = true
        scope.launch {
            delay(CLOSE_DELAY_MS)
            show = false
        }
    }

    fun manage() {
        managing = true
        show = false
    }

    // Close first, then report: a pick, a "follow", "Manage models", or a plain dismiss.
    val finish = {
        val p = picked
        when {
            p != null -> onSelect(p)
            managing -> onManage()
            following -> onFollow?.invoke() ?: onDismiss()
            else -> onDismiss()
        }
    }

    if (compact && all.size <= POPUP_MAX && onFollow == null) {
        CompactPicker(all, ::isSelected, ::pick, show, onDismiss = { show = false }, onManage = ::manage, onFinished = finish)
        return
    }

    val q = query.trim().lowercase()
    val filtered = all.filter { o ->
        val inFilter = filter == ALL || (filter == ON_DEVICE && o.providerId == null) || filter == o.providerId
        inFilter && (q.isEmpty() || o.title.lowercase().contains(q) || o.subtitle.orEmpty().lowercase().contains(q) || o.providerName.lowercase().contains(q))
    }
    val chips = buildList {
        add(ALL to stringResource(R.string.set_all))
        ordered.forEach { add(it.id to it.displayName) }
        if (!cloudOnly && installed.isNotEmpty()) add(ON_DEVICE to stringResource(R.string.tab_on_device))
    }
    val height = LocalConfiguration.current.screenHeightDp.dp * 0.68f
    // Start with the current choice as the third row.
    val startIndex = remember { (all.indexOfFirst(::isSelected) - 2).coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)

    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.set_choose_model),
        onDismissRequest = { show = false },
        onDismissFinished = finish,
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().height(height)) {
            AtomSearchBar(
                query = query,
                onQueryChange = { query = it },
                label = stringResource(R.string.set_search_model),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            )
            if (chips.size > 2) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chips, key = { it.first }) { (id, label) ->
                        FilterChip(label, selected = filter == id) { filter = if (filter == id && id != ALL) ALL else id }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 8.dp)) {
                if (onFollow != null && followLabel != null && q.isEmpty() && filter == ALL) {
                    item(key = "follow") {
                        OptionRow(title = followLabel, subtitle = followHint, logo = null, selected = chosen == null || following, onClick = ::follow)
                    }
                }
                if (filtered.isEmpty()) {
                    item(key = "empty") {
                        EmptyText(if (all.isEmpty()) R.string.set_no_models_yet else R.string.set_no_matching_model)
                    }
                }
                val showHeaders = filter == ALL
                var lastHeader: String? = null
                for (o in filtered) {
                    val header = o.providerId ?: ON_DEVICE
                    if (showHeaders && header != lastHeader) {
                        lastHeader = header
                        item(key = "h-$header") {
                            Text(
                                o.providerName,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                    item(key = o.key) {
                        OptionRow(
                            title = o.title,
                            subtitle = o.subtitle,
                            logo = { OptionLogo(o) },
                            selected = isSelected(o),
                            onClick = { pick(o) },
                        )
                    }
                }
            }
            ManageFooter(::manage, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

/** Shown when nothing matches, or when no provider has models yet. */
@Composable
private fun EmptyText(text: Int) {
    Text(
        stringResource(text),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
    )
}

/** "Manage models": the way to Model providers from the picker (add a provider, enable one, add models). */
@Composable
private fun ManageFooter(onManage: () -> Unit, modifier: Modifier = Modifier) {
    top.yukonga.miuix.kmp.basic.TextButton(
        text = stringResource(R.string.mdl_manage_models),
        onClick = onManage,
        modifier = modifier.fillMaxWidth(),
    )
}

/** The small popup for a short list: logo, name and check per model, under the top bar. */
@Composable
private fun CompactPicker(
    options: List<PickerOption>,
    isSelected: (PickerOption) -> Boolean,
    onPick: (PickerOption) -> Unit,
    show: Boolean,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    onFinished: () -> Unit,
) {
    if (!show) {
        // No exit animation to wait for: report at once.
        androidx.compose.runtime.LaunchedEffect(Unit) { onFinished() }
        return
    }
    val offset = with(LocalDensity.current) { 64.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, offset),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val c = MiuixTheme.colorScheme
        Column(
            Modifier
                .statusBarsPadding()
                .widthIn(min = 248.dp, max = 300.dp)
                .heightIn(max = 380.dp)
                .shadow(12.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(c.surfaceContainer)
                .padding(vertical = 6.dp),
        ) {
            if (options.isEmpty()) EmptyText(R.string.set_no_models_yet)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(options, key = { it.key }) { o ->
                    OptionRow(
                        title = o.title,
                        subtitle = null,
                        logo = { OptionLogo(o) },
                        selected = isSelected(o),
                        onClick = { onPick(o) },
                        outerMargin = 6.dp,
                    )
                }
            }
            ManageFooter(onManage, Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun OptionLogo(o: PickerOption) {
    if (o.providerId == null) {
        Icon(Icons.Rounded.Memory, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
    } else {
        ProviderMark(o.providerId, o.providerName, 20.dp, model = o.model, baseUrl = o.baseUrl)
    }
}

/** A picker row: 14dp squircle, accent tint and check when selected. */
@Composable
private fun OptionRow(
    title: String,
    subtitle: String?,
    logo: (@Composable () -> Unit)?,
    selected: Boolean,
    onClick: () -> Unit,
    outerMargin: androidx.compose.ui.unit.Dp = 12.dp,
) {
    val c = MiuixTheme.colorScheme
    val bg by animateColorAsState(if (selected) c.primary.copy(alpha = 0.14f) else c.primary.copy(alpha = 0f), tween(160), label = "optionBg")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = outerMargin, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (logo != null) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { logo() }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) c.primary else c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = MiuixTheme.textStyles.footnote2, color = c.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = c.primary)
        }
    }
}

/** Provider filter chip; tapping the selected one goes back to All. */
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = MiuixTheme.colorScheme
    val bg by animateColorAsState(if (selected) c.primary.copy(alpha = 0.18f) else c.onSurface.copy(alpha = 0.07f), tween(160), label = "chipBg")
    val fg by animateColorAsState(if (selected) c.primary else c.onSurfaceVariantSummary, tween(160), label = "chipFg")
    Text(
        label,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
