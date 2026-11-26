package atom.web.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.components.GroupRow
import atom.ui.components.RowValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    hint: String? = null,
    singleLine: Boolean = true,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.onSurface)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (value.isEmpty() && placeholder != null) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, maxLines = if (singleLine) 1 else 4)
            }
            val base = MaterialTheme.typography.bodyLarge
            val style = (if (mono) base.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) else base).copy(color = c.onSurface)
            val state = rememberTextFieldState(value)
            val onChange = rememberUpdatedState(onValueChange)
            val current = rememberUpdatedState(value)
            LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { if (it != current.value) onChange.value(it) } }
            LaunchedEffect(value) { if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value) }
            if (secret) {
                BasicSecureTextField(
                    state = state,
                    textStyle = style,
                    cursorBrush = SolidColor(c.onSurface),
                    textObfuscationMode = TextObfuscationMode.RevealLastTyped,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                BasicTextField(
                    state = state,
                    textStyle = style,
                    cursorBrush = SolidColor(c.onSurface),
                    lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(maxHeightInLines = 10),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboard,
                        imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                        autoCorrectEnabled = !mono,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

@Composable
internal fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    GroupRow(title = title, subtitle = subtitle, enabled = enabled, onClick = onClick, trailing = {
        if (selected) Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
    })
}

/** A row showing the current value; tapping opens a menu of [options]. */
@Composable
internal fun <T> PickerRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        GroupRow(title = title, subtitle = subtitle, enabled = enabled, onClick = { open = true }, trailing = { RowValue(label(selected)) })
        AtomMenu(expanded = open, onDismiss = { open = false }) {
            options.forEach { o -> AtomMenuItem(label(o), o == selected) { open = false; onSelect(o) } }
        }
    }
}

@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    atom.ui.components.AtomDialog(show = true, onDismiss = onDismiss, title = title, summary = text) {
        DangerButtons(
            confirm,
            onConfirm = {
                onDismiss()
                onConfirm()
            },
            onDismiss = onDismiss,
        )
    }
}

/** The Miuix dialog button pair with the confirm button in the error colour, for deletes and clears. */
@Composable
private fun DangerButtons(confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        top.yukonga.miuix.kmp.basic.TextButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        top.yukonga.miuix.kmp.basic.TextButton(
            text = confirm,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.error,
                textColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onError,
            ),
        )
    }
}

/** Decoded thumbnails, bounded to a few MB. */
internal object Thumbs {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    suspend fun load(path: String, maxSide: Int): ImageBitmap? {
        val key = "$path@$maxSide"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = File(path)
            if (!f.isFile) return@withContext null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()?.also { cache.put(key, it) }
        }
    }
}

@Composable
internal fun rememberThumb(path: String?, maxSide: Int = 512): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, path, maxSide) { value = path?.let { Thumbs.load(it, maxSide) } }
    return bmp
}

internal object WebIcons {
    val Globe: ImageVector by lazy {
        icon("Globe", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM11,19.93c-3.95,-0.49 -7,-3.85 -7,-7.93 0,-0.62 0.08,-1.21 0.21,-1.79L9,15v1c0,1.1 0.9,2 2,2v1.93zM17.9,17.39c-0.26,-0.81 -1,-1.39 -1.9,-1.39h-1v-3c0,-0.55 -0.45,-1 -1,-1L8,12v-2h2c0.55,0 1,-0.45 1,-1L11,7h2c1.1,0 2,-0.9 2,-2v-0.41c2.93,1.19 5,4.06 5,7.41 0,2.08 -0.8,3.97 -2.1,5.39z")
    }
    val Image: ImageVector by lazy {
        icon("Image", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
    }
    val Gallery: ImageVector by lazy {
        icon("Gallery", "M22,16V4c0,-1.1 -0.9,-2 -2,-2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2zM11,12l2.03,2.71L16,11l4,5H8l3,-4zM2,6v14c0,1.1 0.9,2 2,2h14v-2H4V6H2z")
    }
    val Browser: ImageVector by lazy {
        icon("Browser", "M20,4H4c-1.11,0 -2,0.9 -2,2v12c0,1.1 0.89,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.89,-2 -2,-2zM20,18H4V8h16v10z")
    }
    val Stop: ImageVector by lazy { icon("Stop", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z") }
    val Save: ImageVector by lazy { icon("Save", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z") }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
