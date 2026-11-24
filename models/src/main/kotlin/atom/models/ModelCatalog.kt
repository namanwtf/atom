package atom.models

/**
 * Hand-picked models that run well on a phone.
 *
 * Every URL, size and SHA-256 below was checked with a HEAD request against Hugging Face on
 * 2026-11-25 (`x-linked-size` / `x-linked-etag`). Gated repos return 401 without a token, so their
 * size comes from the repo file listing and their hash is unknown (null).
 */
object ModelCatalog {

    private const val HF = "https://huggingface.co"

    val curated: List<CatalogModel> = listOf(
        // ---- LiteRT-LM (.litertlm): GPU accelerated, lowest battery drain ----
        CatalogModel(
            id = "qwen3-0.6b-litertlm",
            name = "Qwen3 0.6B",
            format = ModelFormat.LiteRtLm,
            url = "$HF/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            sizeBytes = 613_722_264L,
            sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
            minRamGb = 2,
            license = "Apache-2.0",
            gated = false,
            summary = "Tiny and fast: quick replies, short rewrites and simple commands with almost no battery cost.",
            supportsVision = false,
        ),
        CatalogModel(
            id = "gemma-4-e2b-it-litertlm",
            name = "Gemma 4 E2B",
            format = ModelFormat.LiteRtLm,
            url = "$HF/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712L,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            minRamGb = 4,
            license = "Apache-2.0",
            gated = false,
            summary = "Best everyday pick: strong chat and tool calling, understands photos, very efficient on GPU.",
            supportsVision = true,
        ),
        CatalogModel(
            id = "gemma-4-e4b-it-litertlm",
            name = "Gemma 4 E4B",
            format = ModelFormat.LiteRtLm,
            url = "$HF/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            sizeBytes = 3_659_530_240L,
            sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            minRamGb = 6,
            license = "Apache-2.0",
            gated = false,
            summary = "Smarter big brother of E2B: better reasoning and multi-step tasks, still sees images.",
            supportsVision = true,
        ),
        CatalogModel(
            id = "qwen3-4b-instruct-2507-litertlm",
            name = "Qwen3 4B Instruct 2507",
            format = ModelFormat.LiteRtLm,
            url = "$HF/litert-community/Qwen3-4B-Instruct-2507/resolve/main/qwen3_4b_instruct_2507_mixed_int4.litertlm",
            sizeBytes = 2_659_057_664L,
            sha256 = "9e48b165836256f5344d9d044930607b9c47f6ef34e27f82e96881664f3ba2fd",
            minRamGb = 6,
            license = "Apache-2.0",
            gated = false,
            summary = "Reliable at following instructions and calling tools; good for agent tasks and coding help.",
            supportsVision = false,
        ),
        CatalogModel(
            id = "gemma-3n-e2b-it-litertlm",
            name = "Gemma 3n E2B",
            format = ModelFormat.LiteRtLm,
            url = "$HF/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
            sizeBytes = 3_655_827_456L,
            sha256 = null, // gated: hash not exposed without a token
            minRamGb = 4,
            license = "Gemma Terms of Use",
            gated = true,
            summary = "Google's earlier phone-first model with image input; needs a Hugging Face token and accepted license.",
            supportsVision = true,
        ),

        // ---- GGUF (.gguf) for llama.cpp, Q4_K_M quantization ----
        CatalogModel(
            id = "qwen2.5-1.5b-instruct-q4km-gguf",
            name = "Qwen2.5 1.5B Instruct",
            format = ModelFormat.Gguf,
            url = "$HF/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_117_320_736L,
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            minRamGb = 3,
            license = "Apache-2.0",
            gated = false,
            summary = "Small, quick llama.cpp model that handles simple tool calls and short answers well.",
            supportsVision = false,
        ),
        CatalogModel(
            id = "llama-3.2-3b-instruct-q4km-gguf",
            name = "Llama 3.2 3B Instruct",
            format = ModelFormat.Gguf,
            url = "$HF/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 2_019_377_696L,
            sha256 = "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff",
            minRamGb = 4,
            license = "Llama 3.2 Community License",
            gated = false,
            summary = "Friendly general assistant with solid summarising, writing and basic function calling.",
            supportsVision = false,
        ),
        CatalogModel(
            id = "qwen3-4b-q4km-gguf",
            name = "Qwen3 4B",
            format = ModelFormat.Gguf,
            url = "$HF/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            sizeBytes = 2_497_280_256L,
            sha256 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
            minRamGb = 6,
            license = "Apache-2.0",
            gated = false,
            summary = "Can think step by step before answering; strong at tool use, maths and planning.",
            supportsVision = false,
        ),
        CatalogModel(
            id = "phi-4-mini-instruct-q4km-gguf",
            name = "Phi-4 mini Instruct",
            format = ModelFormat.Gguf,
            url = "$HF/bartowski/microsoft_Phi-4-mini-instruct-GGUF/resolve/main/microsoft_Phi-4-mini-instruct-Q4_K_M.gguf",
            sizeBytes = 2_491_874_688L,
            sha256 = "01999f17c39cc3074afae5e9c539bc82d45f2dd7faa3917c66cbef76fce8c0c2",
            minRamGb = 6,
            license = "MIT",
            gated = false,
            summary = "Microsoft's compact reasoner: good at logic, maths and structured output, long context.",
            supportsVision = false,
        ),
        CatalogModel(
            id = "qwen2.5-7b-instruct-q4km-gguf",
            name = "Qwen2.5 7B Instruct",
            format = ModelFormat.Gguf,
            url = "$HF/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            sizeBytes = 4_683_074_240L,
            sha256 = "65b8fcd92af6b4fefa935c625d1ac27ea29dcb6ee14589c55a8f115ceaaa1423",
            minRamGb = 8,
            license = "Apache-2.0",
            gated = false,
            summary = "The most capable here for long, careful answers and complex tool chains; slower and hungrier.",
            supportsVision = false,
        ),
    )

    /** Catalog entry with this id, if any. */
    fun byId(id: String): CatalogModel? = curated.firstOrNull { it.id == id }
}
