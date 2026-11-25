package atom.web.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import atom.web.image.CredentialSource
import atom.web.image.ImageBackend
import atom.web.image.ImageConfig
import atom.web.image.ImageEndpoints
import atom.web.image.ImagePresets
import atom.web.image.ImageSettings
import atom.web.image.JobState
import atom.web.image.Quality
import atom.web.image.Region
import atom.web.search.WebServices
import kotlinx.coroutines.launch

@Composable
internal fun ImageSettingsScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val pref = remember { ImageSettings.of(context) }
    var draft by remember { mutableStateOf(pref.value) }
    var keyDraft by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(false) }
    var keyUnreadable by remember { mutableStateOf(false) }
    var matched by remember { mutableStateOf<String?>(null) }
    var testPrompt by remember { mutableStateOf("A white paper boat on still black water, soft studio light") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testImage by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val b = draft.backend
    val region = when (b) {
        ImageBackend.Qwen -> draft.qwenRegion
        ImageBackend.Seedream -> draft.seedreamRegion
        ImageBackend.SiliconFlow -> draft.siliconRegion
        else -> Region.International
    }
    val vertex = b == ImageBackend.Gemini && draft.geminiVertex

    LaunchedEffect(b, region, vertex) {
        val host = Web.host
        val name = ImageConfig.secretName(b)
        keyUnreadable = runCatching { host.secrets.isUnreadable(name) }.getOrDefault(false)
        keySaved = keyUnreadable || runCatching { !host.secrets.get(name).isNullOrBlank() }.getOrDefault(false)
        keyDraft = ""
        matched = runCatching {
            host.providerKeys.all().firstOrNull { c ->
                if (vertex) c.isVertex else !c.isVertex && ImageEndpoints.matches(b, WebServices.host(c.baseUrl), region, false)
            }?.displayName
        }.getOrNull()
    }

    suspend fun save(): Boolean = runCatching {
        if (keyDraft.isNotBlank()) {
            Web.host.secrets.put(ImageConfig.secretName(b), keyDraft.trim())
            keySaved = true
            keyUnreadable = false
            keyDraft = ""
        }
        pref.set(draft)
        true
    }.getOrElse { status = "Could not save: ${it.message}"; false }

    LargeTitleScreen(
        title = "AI image generation",
        onBack = nav::back,
        bottomBar = { BottomActionBar { PrimaryButton("Save", onClick = { scope.launch { if (save()) status = "Saved." } }) } },
    ) {
        item {
            Group {
                GroupRow("Enable AI image generation", subtitle = "Any chat model can then create and edit images", trailing = {
                    AtomSwitch(draft.enabled, { draft = draft.copy(enabled = it) })
                })
            }
        }
        item { SectionLabel("Service") }
        item {
            Group {
                ImageBackend.entries.forEachIndexed { i, backend ->
                    if (i > 0) GroupDivider(18.dp)
                    ChoiceRow(backend.label, b == backend, { draft = draft.copy(backend = backend) }, subtitle = backendHint(backend))
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Group {
                when (b) {
                    ImageBackend.Gemini -> {
                        PickerRow("Platform", listOf(false, true), draft.geminiVertex, { if (it) "Vertex AI" else "AI Studio" }, { draft = draft.copy(geminiVertex = it) })
                        if (draft.geminiVertex) {
                            GroupDivider(18.dp)
                            FieldRow("Vertex project (optional)", draft.vertexProject, { draft = draft.copy(vertexProject = it.trim()) }, placeholder = "Blank uses express mode", mono = true)
                            GroupDivider(18.dp)
                            FieldRow("Vertex location", draft.vertexLocation, { draft = draft.copy(vertexLocation = it.trim()) }, placeholder = "global", mono = true)
                        }
                    }
                    ImageBackend.Qwen -> PickerRow("Region", Region.entries, draft.qwenRegion, { if (it == Region.China) "Beijing" else "Singapore" }, { draft = draft.copy(qwenRegion = it) })
                    ImageBackend.Seedream -> PickerRow("Region", Region.entries, draft.seedreamRegion, { if (it == Region.China) "China (Volcengine)" else "International (BytePlus)" }, { draft = draft.copy(seedreamRegion = it) })
                    ImageBackend.SiliconFlow -> PickerRow("Region", Region.entries, draft.siliconRegion, { if (it == Region.China) "China (.cn)" else "International (.com)" }, { draft = draft.copy(siliconRegion = it) })
                    else -> GroupRow("Endpoint", subtitle = ImageEndpoints.defaultBase(b, region))
                }
            }
        }
        item { SectionLabel("Model") }
        item {
            val presets = ImagePresets.forBackend(b)
            val current = draft.model(b)
            val custom = presets.none { it.id == current }
            Group {
                presets.forEachIndexed { i, p ->
                    if (i > 0) GroupDivider(18.dp)
                    val sub = listOfNotNull(if (p.recommended) "Recommended" else null, p.note).joinToString(" · ").ifBlank { p.id }
                    ChoiceRow(p.label, current == p.id, { draft = draft.copy(models = draft.models + (b to p.id)) }, subtitle = sub, enabled = !p.retired)
                }
                GroupDivider(18.dp)
                ChoiceRow("Custom model ID", custom, { if (!custom) draft = draft.copy(models = draft.models + (b to "")) })
                if (custom || draft.models[b] == "") {
                    FieldRow("Model ID", if (custom) current else "", { draft = draft.copy(models = draft.models + (b to it.trim())) }, placeholder = presets.first().id, mono = true)
                }
            }
        }
        item { SectionLabel("Credential") }
        item {
            Group {
                ChoiceRow(
                    "Match a model provider",
                    draft.credential == CredentialSource.Auto,
                    { draft = draft.copy(credential = CredentialSource.Auto) },
                    subtitle = matched?.let { "Uses the key and URL of \"$it\"" } ?: "No matching provider with a key yet",
                )
                GroupDivider(18.dp)
                ChoiceRow("Separate key", draft.credential == CredentialSource.Separate, { draft = draft.copy(credential = CredentialSource.Separate) })
                if (draft.credential == CredentialSource.Separate) {
                    FieldRow(
                        "API key", keyDraft, { keyDraft = it },
                        placeholder = when {
                            keyUnreadable -> "Saved key can't be read; enter it again"
                            keySaved -> "Saved"
                            else -> "Not set"
                        },
                        secret = true,
                    )
                    GroupDivider(18.dp)
                    FieldRow(
                        "Base URL (optional)", draft.baseUrls[b].orEmpty(), { draft = draft.copy(baseUrls = draft.baseUrls + (b to it.trim())) },
                        placeholder = ImageEndpoints.defaultBase(b, region, vertex), keyboard = KeyboardType.Uri, mono = true,
                    )
                }
            }
        }
        item { SectionLabel("Defaults") }
        item {
            val preset = draft.preset(b)
            val fixed = preset != null && preset.resolutions.isEmpty()
            Group {
                PickerRow("Aspect ratio", ImagePresets.ASPECTS, draft.aspect, { if (it == "auto") "Auto" else it }, { draft = draft.copy(aspect = it) })
                GroupDivider(18.dp)
                PickerRow("Quality", Quality.entries, draft.quality, { it.name }, { draft = draft.copy(quality = it) },
                    subtitle = if (b == ImageBackend.OpenAI) null else "Used by OpenAI models", enabled = b == ImageBackend.OpenAI)
                GroupDivider(18.dp)
                PickerRow(
                    "Resolution", preset?.resolutions?.takeIf { it.isNotEmpty() } ?: listOf("1K", "2K", "4K"), draft.resolution, { it },
                    { draft = draft.copy(resolution = it) }, subtitle = if (fixed) "Fixed by the model" else null, enabled = !fixed,
                )
                if (b == ImageBackend.Seedream) {
                    GroupDivider(18.dp)
                    GroupRow("Watermark", subtitle = "Adds the provider's AI-generated mark", trailing = {
                        AtomSwitch(draft.seedreamWatermark, { draft = draft.copy(seedreamWatermark = it) })
                    })
                }
                if (b == ImageBackend.Qwen) {
                    GroupDivider(18.dp)
                    GroupRow("Smart prompt enhancement", subtitle = "Lets Qwen expand short prompts", trailing = {
                        AtomSwitch(draft.qwenPromptExtend, { draft = draft.copy(qwenPromptExtend = it) })
                    })
                    GroupDivider(18.dp)
                    GroupRow("Watermark", trailing = { AtomSwitch(draft.qwenWatermark, { draft = draft.copy(qwenWatermark = it) }) })
                }
            }
        }
        item { SectionLabel("Test generation") }
        item {
            AtomCard {
                Group { FieldRow("Prompt", testPrompt, { testPrompt = it }, singleLine = false) }
                CompactButton(if (testing) "Generating…" else "Test generation", enabled = !testing && testPrompt.isNotBlank(), onClick = {
                    scope.launch {
                        if (!save()) return@launch
                        testing = true
                        testImage = null
                        val gen = WebFeature.generator(context)
                        val started = System.currentTimeMillis()
                        val job = gen.run(gen.request(testPrompt, 1, null, null, null, emptyList()), null, save = false)
                        val secs = (System.currentTimeMillis() - started) / 1000
                        testResult = if (job.state == JobState.Done) "Done in ${secs}s with ${job.model}." else "Failed: ${job.error}"
                        testImage = job.images.firstOrNull()?.file
                        testing = false
                    }
                })
                testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                val bmp = rememberThumb(testImage, 1024)
                if (bmp != null) {
                    Image(bmp, "Test image", Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
                }
            }
        }
        status?.let { s -> item { Text(s, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().height(40.dp)) } }
    }
}

private fun backendHint(b: ImageBackend) = when (b) {
    ImageBackend.Gemini -> "Nano Banana · AI Studio or Vertex"
    ImageBackend.OpenAI -> "gpt-image models or any compatible API"
    ImageBackend.Xai -> "Grok Imagine"
    ImageBackend.Seedream -> "Volcengine Ark / BytePlus"
    ImageBackend.Qwen -> "Alibaba Bailian, Beijing or Singapore"
    ImageBackend.SiliconFlow -> "Qwen-Image, Kolors, FLUX"
}
