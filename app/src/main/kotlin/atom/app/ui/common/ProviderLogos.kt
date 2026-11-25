package atom.app.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.app.R
import atom.llm.cloud.ProviderBrands

/*
 * Provider and model brand logos from Lobe Icons (MIT, see NOTICE). Each brand has a variant drawn for dark and
 * for light backgrounds; the app theme (not the system night mode) picks one, because the user can force either.
 */

/** The logo for a brand key from [ProviderBrands]; null when there is none. */
@DrawableRes
fun logoForKey(key: String?, dark: Boolean): Int? = when (key) {
    "openai" -> if (dark) R.drawable.logo_openai_dark else R.drawable.logo_openai_light
    "anthropic" -> if (dark) R.drawable.logo_anthropic_dark else R.drawable.logo_anthropic_light
    "claude" -> if (dark) R.drawable.logo_claude_dark else R.drawable.logo_claude_light
    "gemini" -> if (dark) R.drawable.logo_gemini_dark else R.drawable.logo_gemini_light
    "deepseek" -> if (dark) R.drawable.logo_deepseek_dark else R.drawable.logo_deepseek_light
    "qwen" -> if (dark) R.drawable.logo_qwen_dark else R.drawable.logo_qwen_light
    "kimi" -> if (dark) R.drawable.logo_kimi_dark else R.drawable.logo_kimi_light
    "mimo" -> if (dark) R.drawable.logo_mimo_dark else R.drawable.logo_mimo_light
    "minimax" -> if (dark) R.drawable.logo_minimax_dark else R.drawable.logo_minimax_light
    "stepfun" -> if (dark) R.drawable.logo_stepfun_dark else R.drawable.logo_stepfun_light
    "siliconflow" -> if (dark) R.drawable.logo_siliconflow_dark else R.drawable.logo_siliconflow_light
    "openrouter" -> if (dark) R.drawable.logo_openrouter_dark else R.drawable.logo_openrouter_light
    "xai" -> if (dark) R.drawable.logo_xai_dark else R.drawable.logo_xai_light
    "grok" -> if (dark) R.drawable.logo_grok_dark else R.drawable.logo_grok_light
    "groq" -> if (dark) R.drawable.logo_groq_dark else R.drawable.logo_groq_light
    "mistral" -> if (dark) R.drawable.logo_mistral_dark else R.drawable.logo_mistral_light
    "gemma" -> if (dark) R.drawable.logo_gemma_dark else R.drawable.logo_gemma_light
    "meta" -> if (dark) R.drawable.logo_meta_dark else R.drawable.logo_meta_light
    "ollama" -> if (dark) R.drawable.logo_ollama_dark else R.drawable.logo_ollama_light
    "lmstudio" -> if (dark) R.drawable.logo_lmstudio_dark else R.drawable.logo_lmstudio_light
    "zhipu" -> if (dark) R.drawable.logo_zhipu_dark else R.drawable.logo_zhipu_light
    "doubao" -> if (dark) R.drawable.logo_doubao_dark else R.drawable.logo_doubao_light
    else -> null
}

/**
 * The logo for a provider, by its id or display name (either works: "openai", "OpenAI Copy", "Kimi (Moonshot)").
 * [model] lets aggregators and on-device models show the model family. Null when there is no logo.
 */
@DrawableRes
fun providerLogo(providerIdOrName: String?, dark: Boolean = true, model: String? = null, baseUrl: String? = null): Int? =
    logoForKey(ProviderBrands.key(providerIdOrName, providerIdOrName, baseUrl, model), dark)

/** True when the current theme draws on a dark background. */
@Composable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * A provider's logo at [size]; without one, a monogram disc (first letter of [name]). For lists of providers and
 * models; the home model capsule uses its own fallback.
 */
@Composable
fun ProviderMark(providerId: String?, name: String?, size: Dp, modifier: Modifier = Modifier, model: String? = null, baseUrl: String? = null) {
    val res = logoForKey(ProviderBrands.key(providerId, name, baseUrl, model), isDarkTheme())
    if (res != null) {
        Image(painterResource(res), contentDescription = null, modifier = modifier.size(size))
    } else {
        val colors = MaterialTheme.colorScheme
        Box(modifier.size(size).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text(
                (name ?: providerId).orEmpty().trim().take(1).uppercase().ifEmpty { "?" },
                color = colors.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.46f).sp,
                maxLines = 1,
            )
        }
    }
}

/** Spacing helper for callers that line logos up with 24dp icons. */
val ProviderMarkDefaultSize: Dp = 20.dp
