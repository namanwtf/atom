package atom.app.ui.report

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.app.R
import atom.app.report.ReportCollector
import atom.app.report.ReportDraft
import atom.ui.components.ArrowRow
import atom.ui.components.AtomTextField
import atom.ui.components.BottomActionBar
import atom.ui.components.Group
import atom.ui.components.LargeTitleScreen
import atom.ui.components.PrimaryButton
import atom.ui.components.SectionLabel
import atom.ui.components.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Routes: "report" (from About) and "report/<conversationId>" (from a failed run in that conversation). */
object ReportRoutes {
    const val REPORT = "report"

    fun matches(route: String) = route == REPORT || route.startsWith("$REPORT/")

    fun conversationOf(route: String): String? = route.removePrefix(REPORT).removePrefix("/").takeIf { it.isNotBlank() }

    fun forConversation(id: String?) = if (id.isNullOrBlank()) REPORT else "$REPORT/$id"
}

private enum class Shot { None, Captured, Picked }

@Composable
fun ReportScreen(route: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversationId = remember(route) { ReportRoutes.conversationOf(route) }
    // A screen captured for an earlier report must not ride along: only the chat's report action captures one, right
    // before it opens this screen. Saveable, so turning the phone keeps what was captured or chosen.
    var shot by rememberSaveable {
        val captured = conversationId != null && ReportCollector.screenshotFile(context).isFile
        if (!captured) ReportCollector.clearScreenshot(context)
        mutableStateOf(if (captured) Shot.Captured else Shot.None)
    }
    var shotVersion by remember { mutableIntStateOf(0) }
    var note by rememberSaveable { mutableStateOf("") }
    var withShot by rememberSaveable { mutableStateOf(false) }
    var withConversation by rememberSaveable { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                if (ReportCollector.useImage(context, uri)) {
                    shot = Shot.Picked
                    withShot = true
                    shotVersion++
                }
            }
        } else if (shot == Shot.None) {
            withShot = false
        }
    }

    val draft by produceState<Result<ReportDraft>?>(null, conversationId) {
        value = runCatching { ReportCollector.collect(context, conversationId) }
    }
    var text by remember { mutableStateOf<String?>(null) }
    val hasShot = shot != Shot.None
    LaunchedEffect(draft, note, withShot, withConversation, hasShot) {
        val d = draft?.getOrNull() ?: return@LaunchedEffect
        delay(250)
        text = withContext(Dispatchers.Default) { d.text(note, withConversation && d.hasConversation, withShot && hasShot) }
    }
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, shot, shotVersion) {
        value = if (shot == Shot.None) null else withContext(Dispatchers.IO) {
            runCatching {
                val file = ReportCollector.screenshotFile(context)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outHeight / sample > 1400) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }

    val failed = draft?.exceptionOrNull()
    LargeTitleScreen(
        title = stringResource(R.string.report_title),
        onBack = onBack,
        bottomBar = {
            BottomActionBar {
                PrimaryButton(stringResource(R.string.report_share), onClick = {
                    val t = text ?: return@PrimaryButton
                    runCatching { ReportCollector.share(context, t, withShot && hasShot) }
                        .onFailure { Toast.makeText(context, R.string.report_share_failed, Toast.LENGTH_SHORT).show() }
                }, enabled = text != null)
            }
        },
    ) {
        item(key = "intro") {
            Text(
                stringResource(R.string.report_intro),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            )
        }
        item(key = "note") {
            Group {
                Box(Modifier.padding(12.dp)) {
                    AtomTextField(note, { note = it.take(2000) }, label = stringResource(R.string.report_note_label), minLines = 3)
                }
            }
        }
        item(key = "include-label") { SectionLabel(stringResource(R.string.report_section_include)) }
        item(key = "include") {
            Group {
                SwitchRow(
                    title = stringResource(R.string.report_include_screenshot),
                    checked = withShot && hasShot,
                    onCheckedChange = { on ->
                        if (on && !hasShot) {
                            withShot = true
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } else {
                            withShot = on
                        }
                    },
                    summary = stringResource(
                        when (shot) {
                            Shot.Captured -> R.string.report_include_screenshot_captured
                            Shot.Picked -> R.string.report_include_screenshot_picked
                            Shot.None -> R.string.report_include_screenshot_none
                        },
                    ),
                )
                val image = thumb
                if (withShot && hasShot && image != null) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                        Image(
                            image,
                            contentDescription = stringResource(R.string.report_screenshot_cd),
                            modifier = Modifier.heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    ArrowRow(stringResource(R.string.report_choose_image), onClick = {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                }
                val canConversation = draft?.getOrNull()?.hasConversation == true
                SwitchRow(
                    title = stringResource(R.string.report_include_conversation),
                    checked = withConversation && canConversation,
                    onCheckedChange = { withConversation = it },
                    summary = stringResource(if (canConversation || draft == null) R.string.report_include_conversation_sub else R.string.report_include_conversation_none),
                    enabled = canConversation,
                )
            }
        }
        item(key = "preview-label") { SectionLabel(stringResource(R.string.report_section_preview)) }
        item(key = "preview") {
            Group {
                val shown = when {
                    failed != null -> stringResource(R.string.report_failed, failed.message ?: failed::class.java.simpleName)
                    text == null -> stringResource(R.string.report_preparing)
                    else -> text!!
                }
                SelectionContainer {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(
                            shown,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
