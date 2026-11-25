package atom.web.image

/** The error texts image generation shows to the user (pure, unit-tested). */
object ImageErrors {
    fun retired(preset: ModelPreset): String =
        if (preset.id == "gpt-image-1") {
            "gpt-image-1 has been retired by OpenAI and can no longer generate images. Choose another model, such as " +
                "gpt-image-2.6-flare, in Settings > AI image generation."
        } else {
            "${preset.label} has been retired by its provider. Pick another model in Settings > AI image generation."
        }

    fun noEditing(model: String): String =
        "The current model ($model) does not support image editing. Choose a model that can edit, such as " +
            "Qwen-Image-Edit or a Gemini model, in Settings > AI image generation."

    fun tooManyReferences(model: String, max: Int, given: Int): String =
        "The current model ($model) accepts at most $max reference image${if (max == 1) "" else "s"}; $given were given. " +
            "Use fewer images or pick a model that takes more."

    const val REDIRECT_BLOCKED =
        "The image service tried to redirect the request to another address. Atom blocked it so your API key is not " +
            "sent anywhere else. Check the base URL in Settings > AI image generation."

    /** "<backend>: HTTP <code> – <what it usually means>[ – <server detail>]". */
    fun http(backend: String, code: Int, detail: String?): String = buildString {
        append(backend).append(": HTTP ").append(code).append(" – ").append(explain(code))
        detail?.takeIf { it.isNotBlank() }?.let { append(" – ").append(it.take(300)) }
    }

    fun explain(code: Int): String = when (code) {
        400 -> "the service rejected the request; the model may not support these settings or this prompt"
        401 -> "the API key was rejected; check it in Settings > AI image generation"
        402 -> "the account has no credit or needs billing set up"
        403 -> "the key has no access to this model, or the service is not available in your region"
        404 -> "the model or endpoint was not found; check the model ID and base URL"
        408, 504 -> "the service took too long to answer; try again"
        413 -> "the request is too large; use fewer or smaller reference images"
        422 -> "the service could not process the prompt or images"
        429 -> "rate limit or quota reached; wait a moment or check your plan"
        in 500..599 -> "the image service had a problem; try again later"
        else -> "the request failed"
    }
}
