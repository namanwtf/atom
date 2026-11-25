package atom.app.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.data.AppSettings
import atom.app.data.EngineSelection
import atom.app.data.ModelRole
import atom.app.data.RoleReasoning
import atom.app.data.RoleSetting
import atom.core.llm.ReasoningEffort
import atom.llm.cloud.AuthMethod
import atom.llm.cloud.ProviderConfig
import atom.llm.cloud.Wire
import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.components.AtomSwitch
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowChevron
import atom.ui.components.RowValue
import atom.ui.components.ScreenIcons
import atom.ui.components.SectionLabel
import atom.ui.components.SegmentedControl
import atom.ui.theme.AtomIcons

/** Pages inside the Models flow. Encoded as strings so the back stack survives configuration changes. */
internal sealed interface ModelsPage {
    data object Root : ModelsPage
    data class Provider(val id: String) : ModelsPage
    data class Model(val providerId: String, val modelId: String) : ModelsPage
    data class Add(val kind: NewProviderKind) : ModelsPage
    data object Catalog : ModelsPage
    data class CatalogModels(val catalogId: String) : ModelsPage
    data class Role(val role: ModelRole) : ModelsPage

    fun encode(): String = when (this) {
        Root -> "root"
        is Provider -> "provider$SEP$id"
        is Model -> "model$SEP$providerId$SEP$modelId"
        is Add -> "add$SEP${kind.name}"
        Catalog -> "catalog"
        is CatalogModels -> "catalogModels$SEP$catalogId"
        is Role -> "role$SEP${role.name}"
    }

    companion object {
        const val SEP = "\u0001"

        fun decode(raw: String): ModelsPage {
            val parts = raw.split(SEP)
            return when (parts[0]) {
                "provider" -> Provider(parts[1])
                "model" -> Model(parts[1], parts[2])
                "add" -> Add(NewProviderKind.valueOf(parts[1]))
                "catalog" -> Catalog
                "catalogModels" -> CatalogModels(parts[1])
                "role" -> Role(ModelRole.valueOf(parts[1]))
                else -> Root
            }
        }
    }
}

internal class ModelsNav(private val push: (ModelsPage) -> Unit, private val pop: () -> Unit, private val replace: (ModelsPage) -> Unit) {
    fun open(page: ModelsPage) = push(page)
    fun back() = pop()
    fun replaceWith(page: ModelsPage) = replace(page)
}

@Composable
fun ModelsScreen(vm: ModelsViewModel, onBack: () -> Unit) {
    var stack by rememberSaveable { mutableStateOf(listOf(ModelsPage.Root.encode())) }
    val nav = remember {
        ModelsNav(
            push = { page -> stack = stack + page.encode() },
            pop = { if (stack.size > 1) stack = stack.dropLast(1) },
            replace = { page -> stack = stack.dropLast(1) + page.encode() },
        )
    }
    BackHandler(enabled = stack.size > 1) { nav.back() }
    val back = { if (stack.size > 1) nav.back() else onBack() }

    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
        label = "modelsPage",
    ) { raw ->
        // A page that is animating out must not navigate; only the top page may go back.
        val pageBack = { if (raw == stack.last()) back() }
        when (val page = ModelsPage.decode(raw)) {
            ModelsPage.Root -> ModelsRoot(vm, nav, pageBack)
            is ModelsPage.Provider -> ProviderScreen(vm, nav, page.id, pageBack)
            is ModelsPage.Model -> ModelEditScreen(vm, nav, page.providerId, page.modelId, pageBack)
            is ModelsPage.Add -> AddProviderScreen(vm, nav, page.kind, pageBack)
            ModelsPage.Catalog -> CatalogScreen(vm, nav, pageBack)
            is ModelsPage.CatalogModels -> CatalogModelsScreen(vm, nav, page.catalogId, pageBack)
            is ModelsPage.Role -> RoleScreen(vm, page.role, pageBack)
        }
    }
}

// ================================================================ Root

@Composable
private fun ModelsRoot(vm: ModelsViewModel, nav: ModelsNav, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val hasHfToken by vm.hasHfToken.collectAsStateWithLifecycle()
    val linkModels by vm.linkModels.collectAsStateWithLifecycle()
    val linkError by vm.linkError.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val savedKeys by vm.savedKeys.collectAsStateWithLifecycle()
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val roles by vm.roleSettings.collectAsStateWithLifecycle()

    val s = settings ?: AppSettings()
    var tab by rememberSaveable { mutableIntStateOf(if (s.selection is EngineSelection.Local) 1 else 0) }
    var query by rememberSaveable { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    LargeTitleScreen(title = stringResource(R.string.screen_models), onBack = onBack) {
        defaultItems(vm, s, onPick = { picker = true })
        rolesItems(vm, roles, onOpen = { nav.open(ModelsPage.Role(it)) })

        item(key = "tabs") {
            SegmentedControl(
                listOf(stringResource(R.string.mdl_tab_providers) to AtomIcons.Cloud, stringResource(R.string.tab_on_device) to AtomIcons.Chip),
                tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(top = 26.dp),
            )
        }
        if (tab == 0) {
            providerItems(
                providers = providers,
                query = query,
                onQuery = { query = it },
                selection = s.selection,
                ready = { it.id in savedKeys || it.id in signedIn || it.keyOptional },
                onOpen = { nav.open(ModelsPage.Provider(it)) },
                onAdd = { nav.open(ModelsPage.Add(it)) },
                onCatalog = { nav.open(ModelsPage.Catalog) },
            )
        } else {
            localItems(
                vm = vm,
                settings = s,
                installed = installed,
                hasHfToken = hasHfToken,
                linkModels = linkModels,
                linkError = linkError,
                onDelete = { confirmDelete = it },
            )
            fallbackItems(vm, s, providers)
        }
    }

    if (picker) {
        ModelPickerSheet(
            selected = s.selection,
            onSelect = {
                vm.setDefault(it)
                picker = false
            },
            onDismiss = { picker = false },
            onManage = { picker = false },
        )
    }

    confirmDelete?.let { id ->
        val name = installed.firstOrNull { it.id == id }?.name ?: id
        ConfirmDialog(
            title = stringResource(R.string.delete_model_title),
            text = stringResource(R.string.delete_model_text, name),
            confirm = stringResource(R.string.action_delete),
            onConfirm = { vm.delete(id) },
            onDismiss = { confirmDelete = null },
        )
    }
}

private fun LazyListScope.defaultItems(vm: ModelsViewModel, s: AppSettings, onPick: () -> Unit) {
    item(key = "h-default") { SectionLabel(stringResource(R.string.mdl_section_default)) }
    item(key = "g-default") {
        val selection = s.selection
        Group {
            GroupRow(
                title = selection?.let(vm.info::label) ?: stringResource(R.string.engine_none),
                subtitle = stringResource(R.string.mdl_default_model_sub),
                icon = if (selection is EngineSelection.Local) AtomIcons.Chip else AtomIcons.Cloud,
                onClick = onPick,
                trailing = { RowChevron() },
            )
            GroupDivider()
            GroupRow(
                title = stringResource(R.string.mdl_reasoning_default),
                subtitle = stringResource(R.string.mdl_reasoning_default_sub),
                icon = AtomIcons.Bolt,
                onClick = { vm.setReasoningByDefault(!s.reasoningByDefault) },
                trailing = { AtomSwitch(checked = s.reasoningByDefault, onCheckedChange = vm::setReasoningByDefault) },
            )
            GroupDivider()
            EffortMenuRow(
                title = stringResource(R.string.mdl_reasoning_effort_default),
                icon = ScreenIcons.Tune,
                current = s.defaultReasoning,
                levels = ReasoningEffort.entries.filter { it != ReasoningEffort.Off },
                nullLabel = stringResource(R.string.mdl_effort_model_default),
                onSelect = vm::setDefaultReasoning,
            )
        }
    }
}

private fun LazyListScope.rolesItems(vm: ModelsViewModel, roles: Map<ModelRole, RoleSetting>, onOpen: (ModelRole) -> Unit) {
    item(key = "h-roles") { SectionLabel(stringResource(R.string.mdl_section_roles)) }
    item(key = "g-roles") {
        Group {
            ModelRole.entries.forEachIndexed { i, role ->
                if (i > 0) GroupDivider()
                val setting = roles[role] ?: RoleSetting()
                val value = when {
                    role == ModelRole.Image -> stringResource(R.string.mdl_role_image_value)
                    setting.followsPrimary -> stringResource(if (role == ModelRole.Title) R.string.mdl_follow_conversation else R.string.mdl_follow_primary)
                    else -> setting.selection?.let(vm.info::modelName).orEmpty()
                }
                GroupRow(
                    title = stringResource(roleTitle(role)),
                    subtitle = value,
                    onClick = { onOpen(role) },
                    trailing = { RowChevron() },
                )
            }
        }
    }
}

internal fun roleTitle(role: ModelRole): Int = when (role) {
    ModelRole.Assistant -> R.string.mdl_role_assistant
    ModelRole.Subagent -> R.string.mdl_role_subagent
    ModelRole.Title -> R.string.mdl_role_title
    ModelRole.Scheduled -> R.string.mdl_role_scheduled
    ModelRole.Image -> R.string.mdl_role_image
}

private fun LazyListScope.providerItems(
    providers: List<ProviderConfig>,
    query: String,
    onQuery: (String) -> Unit,
    selection: EngineSelection?,
    ready: (ProviderConfig) -> Boolean,
    onOpen: (String) -> Unit,
    onAdd: (NewProviderKind) -> Unit,
    onCatalog: () -> Unit,
) {
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) providers else providers.filter { p ->
        p.displayName.lowercase().contains(q) || p.baseUrl.lowercase().contains(q) || p.models.any { it.id.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
    }
    item(key = "search") {
        SearchField(query, onQuery, stringResource(R.string.mdl_search_providers), Modifier.padding(top = 14.dp))
    }
    item(key = "h-add") { SectionLabel(stringResource(R.string.mdl_add_provider)) }
    item(key = "g-add") {
        Group {
            GroupRow(
                title = stringResource(R.string.mdl_add_openai),
                subtitle = stringResource(R.string.mdl_add_openai_sub),
                icon = Icons.Filled.Add,
                onClick = { onAdd(NewProviderKind.OpenAiCompatible) },
                trailing = { RowChevron() },
            )
            GroupDivider()
            GroupRow(
                title = stringResource(R.string.mdl_add_anthropic),
                subtitle = stringResource(R.string.mdl_add_anthropic_sub),
                icon = Icons.Filled.Add,
                onClick = { onAdd(NewProviderKind.Anthropic) },
                trailing = { RowChevron() },
            )
            GroupDivider()
            GroupRow(
                title = stringResource(R.string.mdl_add_gemini),
                subtitle = stringResource(R.string.mdl_add_gemini_sub),
                icon = Icons.Filled.Add,
                onClick = { onAdd(NewProviderKind.Gemini) },
                trailing = { RowChevron() },
            )
            GroupDivider()
            GroupRow(
                title = stringResource(R.string.mdl_add_catalog),
                subtitle = stringResource(R.string.mdl_add_catalog_sub),
                icon = ScreenIcons.Storage,
                onClick = onCatalog,
                trailing = { RowChevron() },
            )
        }
    }
    item(key = "h-configured") { SectionLabel(stringResource(R.string.mdl_configured, providers.size)) }
    item(key = "g-configured") {
        Group {
            if (shown.isEmpty()) {
                GroupRow(title = stringResource(R.string.mdl_no_match))
            }
            shown.forEachIndexed { i, p ->
                if (i > 0) GroupDivider(inset = 70.dp)
                ProviderRow(
                    config = p,
                    current = (selection as? EngineSelection.Cloud)?.providerId == p.id,
                    ready = ready(p),
                    onClick = { onOpen(p.id) },
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(config: ProviderConfig, current: Boolean, ready: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val subtitle = buildList {
        add(config.baseUrl.removePrefix("https://").removePrefix("http://").ifEmpty { stringResource(R.string.mdl_no_base_url) })
        add(protocolLabel(config.wire))
        add(androidx.compose.ui.res.pluralStringResource(R.plurals.mdl_model_count, config.models.size, config.models.size))
    }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderMark(config.displayName)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    config.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (config.enabled) c.onSurface else c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (config.builtIn) QuietPill(stringResource(R.string.mdl_badge_builtin))
                if (config.auth != AuthMethod.ApiKey) QuietPill(stringResource(R.string.mdl_badge_account))
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                !config.enabled -> stringResource(R.string.mdl_status_disabled)
                !ready -> stringResource(if (config.auth == AuthMethod.ApiKey) R.string.mdl_status_no_key else R.string.mdl_status_signed_out)
                else -> null
            }
            if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        if (current) {
            CheckMark(true, stringResource(R.string.mdl_badge_current))
        } else {
            RowChevron()
        }
    }
}

private fun LazyListScope.fallbackItems(vm: ModelsViewModel, s: AppSettings, providers: List<ProviderConfig>) {
    item(key = "h-fallback") { SectionLabel(stringResource(R.string.settings_fallback)) }
    item(key = "g-fallback") {
        var picker by remember { mutableStateOf(false) }
        Group {
            GroupRow(
                title = s.cloudFallback?.let(vm.info::label) ?: stringResource(R.string.settings_fallback_none),
                subtitle = stringResource(R.string.cloud_fallback_explain),
                icon = AtomIcons.Bolt,
                onClick = { picker = true },
                trailing = { RowChevron() },
            )
            if (s.cloudFallback != null) {
                GroupDivider()
                GroupRow(title = stringResource(R.string.mdl_fallback_clear), onClick = { vm.setFallback(null) })
            }
        }
        if (picker) {
            ModelPickerSheet(
                selected = s.cloudFallback,
                onSelect = {
                    if (it is EngineSelection.Cloud) vm.setFallback(it)
                    picker = false
                },
                onDismiss = { picker = false },
                onManage = { picker = false },
                cloudOnly = true,
            )
        }
    }
}

// ================================================================ Roles

@Composable
private fun RoleScreen(vm: ModelsViewModel, role: ModelRole, onBack: () -> Unit) {
    val roles by vm.roleSettings.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val setting = roles[role] ?: if (role == ModelRole.Title) RoleSetting(reasoning = RoleReasoning.Auto) else RoleSetting()
    var picker by remember { mutableStateOf(false) }
    val followLabel = stringResource(if (role == ModelRole.Title) R.string.mdl_follow_conversation else R.string.mdl_follow_primary)

    LargeTitleScreen(title = stringResource(roleTitle(role)), onBack = onBack) {
        item(key = "intro") { Note(stringResource(roleDescription(role)), Modifier.padding(top = 6.dp)) }
        if (role == ModelRole.Title) {
            item(key = "g-auto") {
                val on = settings?.autoTitles ?: true
                Group(Modifier.padding(top = 18.dp)) {
                    GroupRow(
                        title = stringResource(R.string.mdl_auto_titles),
                        subtitle = stringResource(R.string.mdl_auto_titles_sub),
                        onClick = { vm.setAutoTitles(!on) },
                        trailing = { AtomSwitch(checked = on, onCheckedChange = vm::setAutoTitles) },
                    )
                }
            }
        }
        if (role == ModelRole.Image) return@LargeTitleScreen

        item(key = "h-model") { SectionLabel(stringResource(R.string.cloud_model)) }
        item(key = "g-model") {
            Group {
                GroupRow(
                    title = followLabel,
                    onClick = { vm.setRole(role, setting.copy(selection = null)) },
                    trailing = { CheckMark(setting.followsPrimary) },
                )
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = setting.selection?.let(vm.info::label) ?: stringResource(R.string.mdl_choose_model),
                    subtitle = if (setting.selection != null) stringResource(R.string.mdl_role_specific) else null,
                    onClick = { picker = true },
                    trailing = { if (setting.selection != null) CheckMark(true) else RowChevron() },
                )
            }
        }

        item(key = "h-reasoning") { SectionLabel(stringResource(R.string.mdl_reasoning)) }
        item(key = "g-reasoning") {
            val levels = setting.selection?.let(vm.info::reasoningLevels) ?: ReasoningEffort.entries
            val options = buildList {
                add(RoleReasoning.Follow to null)
                if (role == ModelRole.Title) add(RoleReasoning.Auto to null)
                add(RoleReasoning.ModelDefault to null)
                levels.forEach { add(RoleReasoning.Level to it) }
            }
            Group {
                options.forEachIndexed { i, (mode, effort) ->
                    if (i > 0) GroupDivider(inset = 18.dp)
                    val checked = setting.reasoning == mode && (mode != RoleReasoning.Level || setting.effort == effort)
                    GroupRow(
                        title = when (mode) {
                            RoleReasoning.Follow -> stringResource(R.string.mdl_reasoning_follow)
                            RoleReasoning.Auto -> stringResource(R.string.mdl_reasoning_auto_minimal)
                            RoleReasoning.ModelDefault -> stringResource(R.string.mdl_effort_model_default)
                            RoleReasoning.Level -> effortLabel(effort!!)
                        },
                        onClick = { vm.setRole(role, setting.copy(reasoning = mode, effort = effort)) },
                        trailing = { CheckMark(checked) },
                    )
                }
            }
        }
        if (role == ModelRole.Scheduled) item(key = "n-sched") { Note(stringResource(R.string.mdl_role_scheduled_note)) }
    }

    if (picker) {
        ModelPickerSheet(
            selected = setting.selection,
            onSelect = {
                vm.setRole(role, setting.copy(selection = it))
                picker = false
            },
            onDismiss = { picker = false },
            onManage = { picker = false },
        )
    }
}

private fun roleDescription(role: ModelRole): Int = when (role) {
    ModelRole.Assistant -> R.string.mdl_role_assistant_desc
    ModelRole.Subagent -> R.string.mdl_role_subagent_desc
    ModelRole.Title -> R.string.mdl_role_title_desc
    ModelRole.Scheduled -> R.string.mdl_role_scheduled_desc
    ModelRole.Image -> R.string.mdl_role_image_desc
}

// ================================================================ Shared pieces

@Composable
internal fun effortLabel(effort: ReasoningEffort): String = stringResource(
    when (effort) {
        ReasoningEffort.Off -> R.string.mdl_effort_off
        ReasoningEffort.Low -> R.string.mdl_effort_low
        ReasoningEffort.Medium -> R.string.mdl_effort_medium
        ReasoningEffort.High -> R.string.mdl_effort_high
        ReasoningEffort.XHigh -> R.string.mdl_effort_xhigh
        ReasoningEffort.Max -> R.string.mdl_effort_max
    },
)

@Composable
internal fun protocolLabel(wire: Wire): String = stringResource(
    when (wire) {
        Wire.OpenAiChat -> R.string.mdl_wire_chat
        Wire.OpenAiResponses -> R.string.mdl_wire_responses
        Wire.Anthropic -> R.string.mdl_wire_anthropic
        Wire.Gemini -> R.string.mdl_wire_gemini
        Wire.Vertex -> R.string.mdl_wire_vertex
    },
)

internal fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 < 100_000 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.2f", tokens / 1_000_000.0).trimEnd('0').trimEnd('.') + "M"
    tokens >= 1_000 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}

@Composable
internal fun EffortMenuRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    current: ReasoningEffort?,
    levels: List<ReasoningEffort>,
    nullLabel: String,
    onSelect: (ReasoningEffort?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        GroupRow(
            title = title,
            icon = icon,
            onClick = { open = true },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RowValue(current?.let { effortLabel(it) } ?: nullLabel)
                    Icon(ScreenIcons.ChevronDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
        AtomMenu(expanded = open, onDismiss = { open = false }) {
            AtomMenuItem(text = nullLabel, checked = current == null) {
                open = false
                onSelect(null)
            }
            levels.forEach { level ->
                AtomMenuItem(text = effortLabel(level), checked = current == level) {
                    open = false
                    onSelect(level)
                }
            }
        }
    }
}

@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        title = { Text(title) },
        text = { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface) }
        },
    )
}

@Composable
internal fun CheckRow(title: String, checked: Boolean, onToggle: () -> Unit, subtitle: String? = null) {
    GroupRow(
        title = title,
        subtitle = subtitle,
        onClick = onToggle,
        trailing = {
            if (checked) {
                CheckMark(true)
            } else {
                Box(Modifier.size(24.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
            }
        },
    )
}
