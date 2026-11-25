package atom.app.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.data.AppSettings
import atom.app.data.EngineSelection
import atom.app.data.ModelRole
import atom.app.data.RoleReasoning
import atom.app.data.RoleSetting
import atom.app.ui.common.ProviderMark
import atom.core.llm.ReasoningEffort
import atom.llm.cloud.ProviderConfig
import atom.llm.cloud.Wire
import atom.ui.components.ArrowRow
import atom.ui.components.AtomCheckbox
import atom.ui.components.AtomDialog
import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.components.AtomSearchBar
import atom.ui.components.AtomSwitch
import atom.ui.components.DialogButtons
import atom.ui.components.DropdownRow
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowChevron
import atom.ui.components.RowValue
import atom.ui.components.ScreenIcons
import atom.ui.components.SectionLabel
import atom.ui.components.SwitchRow
import atom.ui.theme.AtomIcons
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Pages inside the Models flow. Encoded as strings so the back stack survives configuration changes. */
internal sealed interface ModelsPage {
    data object Root : ModelsPage
    data class Provider(val id: String) : ModelsPage
    data class Model(val providerId: String, val modelId: String) : ModelsPage
    data class Add(val kind: NewProviderKind) : ModelsPage
    data object Catalog : ModelsPage
    data class CatalogModels(val catalogId: String) : ModelsPage
    data class Role(val role: ModelRole) : ModelsPage
    data object OnDevice : ModelsPage
    data object Titles : ModelsPage

    fun encode(): String = when (this) {
        Root -> "root"
        is Provider -> "provider$SEP$id"
        is Model -> "model$SEP$providerId$SEP$modelId"
        is Add -> "add$SEP${kind.name}"
        Catalog -> "catalog"
        is CatalogModels -> "catalogModels$SEP$catalogId"
        is Role -> "role$SEP${role.name}"
        OnDevice -> "onDevice"
        Titles -> "titles"
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
                "onDevice" -> OnDevice
                "titles" -> Titles
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

/** Which page the Models flow opens on (Settings links straight to on-device models and to Auto-generate titles). */
enum class ModelsStart { Providers, OnDevice, Titles }

@Composable
fun ModelsScreen(vm: ModelsViewModel, onBack: () -> Unit, start: ModelsStart = ModelsStart.Providers) {
    val first = when (start) {
        ModelsStart.Providers -> ModelsPage.Root
        ModelsStart.OnDevice -> ModelsPage.OnDevice
        ModelsStart.Titles -> ModelsPage.Titles
    }
    var stack by rememberSaveable { mutableStateOf(listOf(first.encode())) }
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
            ModelsPage.OnDevice -> OnDeviceScreen(vm, pageBack)
            ModelsPage.Titles -> TitlesScreen(vm, pageBack)
        }
    }
}

// ================================================================ Root: Model providers

@Composable
private fun ModelsRoot(vm: ModelsViewModel, nav: ModelsNav, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val roles by vm.roleSettings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<ProviderConfig?>(null) }
    val selection = settings?.selection as? EngineSelection.Cloud

    LargeTitleScreen(title = stringResource(R.string.settings_model_providers), onBack = onBack) {
        item(key = "search") {
            AtomSearchBar(
                query = query,
                onQueryChange = { query = it },
                label = stringResource(R.string.set_search_provider),
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
        item(key = "h-add") { SectionLabel(stringResource(R.string.set_add_new_provider)) }
        item(key = "g-add") {
            Group {
                AddRow(
                    title = stringResource(R.string.mdl_add_catalog),
                    summary = stringResource(R.string.mdl_add_catalog_sub),
                    start = {
                        Icon(
                            Icons.Rounded.TravelExplore,
                            null,
                            Modifier.padding(end = 8.dp).size(24.dp).padding(1.dp),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    },
                    onClick = { nav.open(ModelsPage.Catalog) },
                )
                AddRow(
                    title = stringResource(R.string.mdl_add_openai),
                    summary = stringResource(R.string.mdl_add_openai_sub),
                    start = { ProviderMark("openai", "OpenAI", 24.dp, Modifier.padding(end = 8.dp)) },
                    onClick = { nav.open(ModelsPage.Add(NewProviderKind.OpenAiCompatible)) },
                )
                AddRow(
                    title = stringResource(R.string.mdl_add_anthropic),
                    summary = stringResource(R.string.mdl_add_anthropic_sub),
                    start = { ProviderMark("anthropic", "Anthropic", 24.dp, Modifier.padding(end = 8.dp)) },
                    onClick = { nav.open(ModelsPage.Add(NewProviderKind.Anthropic)) },
                )
                AddRow(
                    title = stringResource(R.string.mdl_add_gemini),
                    summary = stringResource(R.string.mdl_add_gemini_sub),
                    start = { ProviderMark("gemini", "Gemini", 24.dp, Modifier.padding(end = 8.dp)) },
                    onClick = { nav.open(ModelsPage.Add(NewProviderKind.Gemini)) },
                )
            }
        }

        val shown = ProviderSearch.filter(providers, query)
        item(key = "h-configured") { SectionLabel(stringResource(R.string.set_configured_providers, providers.size)) }
        item(key = "g-configured") {
            Group {
                if (shown.isEmpty()) {
                    GroupRow(
                        title = stringResource(if (providers.isEmpty()) R.string.set_no_providers else R.string.set_no_matching_providers),
                        enabled = false,
                    )
                }
                shown.forEach { p ->
                    ProviderRow(
                        config = p,
                        current = selection?.providerId == p.id,
                        onOpen = { nav.open(ModelsPage.Provider(p.id)) },
                        onSelect = { vm.setDefault(EngineSelection.Cloud(p.id, p.primaryModel)) },
                        onLongPress = if (p.builtIn) null else ({ confirmRemove = p }),
                    )
                }
            }
        }

        item(key = "h-more") { SectionLabel(stringResource(R.string.set_more_models)) }
        item(key = "g-more") {
            Group {
                ArrowRow(
                    title = stringResource(R.string.set_on_device),
                    summary = (settings?.selection as? EngineSelection.Local)?.let(vm.info::label) ?: stringResource(R.string.set_on_device_sub),
                    icon = Icons.Rounded.Memory,
                    onClick = { nav.open(ModelsPage.OnDevice) },
                )
            }
        }
        item(key = "h-roles") { SectionLabel(stringResource(R.string.mdl_section_roles)) }
        item(key = "g-roles") {
            Group {
                ModelRole.entries.forEach { role ->
                    val setting = roles[role] ?: RoleSetting()
                    val value = when {
                        role == ModelRole.Image -> stringResource(R.string.mdl_role_image_value)
                        setting.followsPrimary -> stringResource(if (role == ModelRole.Title) R.string.mdl_follow_conversation else R.string.mdl_follow_primary)
                        else -> setting.selection?.let(vm.info::modelName).orEmpty()
                    }
                    ArrowRow(
                        title = stringResource(roleTitle(role)),
                        summary = value,
                        onClick = { nav.open(if (role == ModelRole.Title) ModelsPage.Titles else ModelsPage.Role(role)) },
                    )
                }
            }
        }
    }

    val removing = confirmRemove
    AtomDialog(
        show = removing != null,
        onDismiss = { confirmRemove = null },
        title = stringResource(R.string.set_remove_provider_title),
        summary = removing?.let { stringResource(R.string.set_remove_provider_text, it.displayName) },
    ) {
        DialogButtons(
            confirmText = stringResource(R.string.set_delete),
            onConfirm = {
                removing?.let { vm.removeProvider(it.id) }
                confirmRemove = null
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { confirmRemove = null },
        )
    }
}

/** An "Add new provider" row: logo or icon, title, summary and the arrow. */
@Composable
private fun AddRow(title: String, summary: String, start: @Composable () -> Unit, onClick: () -> Unit) {
    ArrowPreference(title = title, summary = summary, startAction = start, onClick = onClick)
}

/**
 * A configured provider: logo, name ("current" tag when selected), base URL, "type · N models · built-in" line,
 * and the round check that makes it the current provider. Tap opens it; long-press offers removal (not built-ins).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProviderRow(config: ProviderConfig, current: Boolean, onOpen: () -> Unit, onSelect: () -> Unit, onLongPress: (() -> Unit)?) {
    val c = MiuixTheme.colorScheme
    val line = listOfNotNull(
        stringResource(providerTypeLabel(config.wire)),
        stringResource(R.string.set_models_n, config.models.size),
        if (config.builtIn) stringResource(R.string.set_tag_builtin) else null,
        if (!config.enabled) stringResource(R.string.set_tag_disabled) else null,
    ).joinToString(" · ")
    BasicComponent(
        modifier = Modifier
            .alpha(if (config.enabled) 1f else 0.6f)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        startAction = {
            ProviderMark(config.id, config.displayName, 24.dp, Modifier.padding(end = 8.dp), baseUrl = config.baseUrl)
        },
        endActions = {
            AtomCheckbox(checked = current, onCheckedChange = { if (it) onSelect() }, modifier = Modifier.align(Alignment.CenterVertically))
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                config.displayName,
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Medium,
                color = if (current) c.primary else c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (current) {
                Spacer(Modifier.width(6.dp))
                CurrentTag()
            }
        }
        Text(
            config.baseUrl.ifEmpty { stringResource(R.string.mdl_no_base_url) },
            style = MiuixTheme.textStyles.body2,
            color = c.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            line,
            style = MiuixTheme.textStyles.footnote1,
            color = c.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The small "current" chip next to the selected provider or model. */
@Composable
internal fun CurrentTag() {
    val c = MiuixTheme.colorScheme
    Text(
        stringResource(R.string.set_tag_current),
        style = MiuixTheme.textStyles.footnote2,
        fontWeight = FontWeight.Medium,
        color = c.onTertiaryContainer,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.tertiaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

internal fun providerTypeLabel(wire: Wire): Int = when (wire) {
    Wire.OpenAiChat -> R.string.set_type_openai
    Wire.OpenAiResponses -> R.string.set_type_responses
    Wire.Anthropic -> R.string.set_type_anthropic
    Wire.Gemini -> R.string.set_type_gemini
    Wire.Vertex -> R.string.set_type_vertex
}

// ================================================================ On-device models

@Composable
private fun OnDeviceScreen(vm: ModelsViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val hasHfToken by vm.hasHfToken.collectAsStateWithLifecycle()
    val linkModels by vm.linkModels.collectAsStateWithLifecycle()
    val linkError by vm.linkError.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val s = settings ?: AppSettings()

    LargeTitleScreen(title = stringResource(R.string.set_on_device), onBack = onBack) {
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

// ================================================================ Auto-generate titles

@Composable
private fun TitlesScreen(vm: ModelsViewModel, onBack: () -> Unit) {
    val roles by vm.roleSettings.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val setting = roles[ModelRole.Title] ?: RoleSetting(reasoning = RoleReasoning.Auto)
    val on = settings?.autoTitles ?: true
    var picker by remember { mutableStateOf(false) }
    val primaryName = settings?.selection?.let(vm.info::modelName) ?: stringResource(R.string.set_not_configured)
    val followLabel = stringResource(R.string.set_titles_follow, primaryName)

    LargeTitleScreen(title = stringResource(R.string.settings_auto_titles), onBack = onBack) {
        item(key = "h") { SectionLabel(stringResource(R.string.set_titles_group)) }
        item(key = "g") {
            Group {
                SwitchRow(
                    title = stringResource(R.string.set_titles_enable),
                    checked = on,
                    onCheckedChange = vm::setAutoTitles,
                )
                ArrowRow(
                    title = stringResource(R.string.set_titles_model),
                    summary = setting.selection?.let(vm.info::label) ?: followLabel,
                    onClick = { picker = true },
                    enabled = on,
                )
                val levels = (setting.selection ?: settings?.selection)?.let(vm.info::reasoningLevels).orEmpty().filter { it != ReasoningEffort.Off }
                val options = buildList {
                    add(RoleReasoning.Auto to null)
                    add(RoleReasoning.ModelDefault to null)
                    levels.forEach { add(RoleReasoning.Level to it) }
                    add(RoleReasoning.Follow to null)
                }
                val labels = options.map { (mode, effort) ->
                    when (mode) {
                        RoleReasoning.Auto -> stringResource(R.string.set_titles_auto)
                        RoleReasoning.ModelDefault -> stringResource(R.string.mdl_effort_model_default)
                        RoleReasoning.Level -> effortLabel(effort!!)
                        RoleReasoning.Follow -> stringResource(R.string.mdl_reasoning_follow)
                    }
                }
                val index = options.indexOfFirst { (mode, effort) -> mode == setting.reasoning && (mode != RoleReasoning.Level || effort == setting.effort) }
                DropdownRow(
                    title = stringResource(R.string.set_titles_reasoning),
                    items = labels,
                    selectedIndex = index.coerceAtLeast(0),
                    onSelect = { i ->
                        val (mode, effort) = options[i]
                        vm.setRole(ModelRole.Title, setting.copy(reasoning = mode, effort = effort))
                    },
                    enabled = on,
                )
            }
        }
        item(key = "n") { Note(stringResource(R.string.set_titles_note)) }
    }

    if (picker) {
        ModelPickerSheet(
            selected = setting.selection,
            onSelect = {
                vm.setRole(ModelRole.Title, setting.copy(selection = it))
                picker = false
            },
            onDismiss = { picker = false },
            onManage = { picker = false },
            compact = false,
            followLabel = followLabel,
            followHint = stringResource(R.string.set_titles_follow_hint),
            onFollow = {
                vm.setRole(ModelRole.Title, setting.copy(selection = null))
                picker = false
            },
        )
    }
}

/** Provider search: name, base URL and protocol, case-insensitive. */
internal object ProviderSearch {
    fun filter(providers: List<ProviderConfig>, query: String): List<ProviderConfig> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return providers
        return providers.filter { p ->
            p.displayName.lowercase().contains(q) || p.baseUrl.lowercase().contains(q) || p.wire.name.lowercase().contains(q) ||
                p.id.lowercase().contains(q)
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
                compact = false,
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
            compact = false,
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
