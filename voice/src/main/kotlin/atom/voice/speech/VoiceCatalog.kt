package atom.voice.speech

/** Voices are listed under "Female" and "Male". */
enum class VoiceGender { Female, Male }

/** One preset voice: the id sent to the service and the name shown to the user. */
data class VoicePreset(val id: String, val name: String, val note: String? = null, val gender: VoiceGender? = null)

/** Preset voices per spoken-answer service, plus matching a saved id back to its preset. */
object VoiceCatalog {
    val qwen = listOf(
        VoicePreset("Cherry", "Cherry", gender = VoiceGender.Female),
        VoicePreset("Serena", "Serena", gender = VoiceGender.Female),
        VoicePreset("Ethan", "Ethan", gender = VoiceGender.Male),
        VoicePreset("Chelsie", "Chelsie", gender = VoiceGender.Female),
        VoicePreset("Momo", "Momo", gender = VoiceGender.Female),
        VoicePreset("Vivian", "Vivian", gender = VoiceGender.Female),
        VoicePreset("Moon", "Moon", gender = VoiceGender.Male),
        VoicePreset("Maia", "Maia", gender = VoiceGender.Female),
        VoicePreset("Kai", "Kai", gender = VoiceGender.Male),
        VoicePreset("Nofish", "Nofish", gender = VoiceGender.Male),
        VoicePreset("Bella", "Bella", gender = VoiceGender.Female),
        VoicePreset("Katerina", "Katerina", gender = VoiceGender.Female),
        VoicePreset("Vincent", "Vincent", gender = VoiceGender.Male),
        VoicePreset("Neil", "Neil", gender = VoiceGender.Male),
        VoicePreset("Jennifer", "Jennifer", "American English", gender = VoiceGender.Female),
        VoicePreset("Ryan", "Ryan", gender = VoiceGender.Male),
        VoicePreset("Dylan", "Dylan", "Beijing dialect, male", gender = VoiceGender.Male),
        VoicePreset("Jada", "Jada", "Shanghai dialect, female", gender = VoiceGender.Female),
        VoicePreset("Sunny", "Sunny", "Sichuan dialect, female", gender = VoiceGender.Female),
        VoicePreset("Rocky", "Rocky", "Cantonese, male", gender = VoiceGender.Male),
        VoicePreset("Kiki", "Kiki", "Cantonese, female", gender = VoiceGender.Female),
    )

    val doubao = listOf(
        VoicePreset("zh_female_vv_uranus_bigtts", "Vivi", gender = VoiceGender.Female),
        VoicePreset("zh_female_xiaohe_uranus_bigtts", "Xiaohe", gender = VoiceGender.Female),
        VoicePreset("zh_male_m191_uranus_bigtts", "Yunzhou", gender = VoiceGender.Male),
        VoicePreset("zh_male_taocheng_uranus_bigtts", "Xiaotian", gender = VoiceGender.Male),
        VoicePreset("zh_male_liufei_uranus_bigtts", "Liufei", gender = VoiceGender.Male),
        VoicePreset("zh_female_qingxinnvsheng_uranus_bigtts", "Fresh Voice", gender = VoiceGender.Female),
        VoicePreset("zh_female_cancan_uranus_bigtts", "Cancan", gender = VoiceGender.Female),
        VoicePreset("zh_female_tianmeixiaoyuan_uranus_bigtts", "Xiaoyuan", gender = VoiceGender.Female),
        VoicePreset("zh_female_tianmeitaozi_uranus_bigtts", "Taozi", gender = VoiceGender.Female),
        VoicePreset("zh_female_shuangkuaisisi_uranus_bigtts", "Sisi", gender = VoiceGender.Female),
        VoicePreset("zh_female_linjianvhai_uranus_bigtts", "Girl Next Door", gender = VoiceGender.Female),
        VoicePreset("zh_male_shaonianzixin_uranus_bigtts", "Zixin", gender = VoiceGender.Male),
        VoicePreset("zh_male_wennuanahu_uranus_bigtts", "Ahu", gender = VoiceGender.Male),
        VoicePreset("zh_male_ruyayichen_uranus_bigtts", "Yichen", gender = VoiceGender.Male),
        VoicePreset("zh_male_jieshuoxiaoming_uranus_bigtts", "Xiaoming", gender = VoiceGender.Male),
        VoicePreset("zh_male_shenyeboke_uranus_bigtts", "Late Night Podcast", gender = VoiceGender.Male),
    )

    val gemini = listOf(
        VoicePreset("Kore", "Kore", "Firm", gender = VoiceGender.Female),
        VoicePreset("Puck", "Puck", "Upbeat", gender = VoiceGender.Male),
        VoicePreset("Charon", "Charon", "Informative", gender = VoiceGender.Male),
        VoicePreset("Aoede", "Aoede", "Breezy", gender = VoiceGender.Female),
        VoicePreset("Zephyr", "Zephyr", "Bright", gender = VoiceGender.Female),
        VoicePreset("Leda", "Leda", "Youthful", gender = VoiceGender.Female),
        VoicePreset("Sulafat", "Sulafat", "Warm", gender = VoiceGender.Female),
        VoicePreset("Achernar", "Achernar", "Soft", gender = VoiceGender.Female),
    )

    val geminiModels = listOf(
        VoicePreset(GeminiTtsModels.FLASH, "Gemini 3.8 Flash", "Best quality"),
        VoicePreset(GeminiTtsModels.FLASH_LITE, "Gemini 3.8 Flash-Lite", "Lower latency"),
    )

    val miniMax = listOf(
        VoicePreset("female-chengshu", "Mature woman", gender = VoiceGender.Female),
        VoicePreset("female-tianmei", "Sweet woman", gender = VoiceGender.Female),
        VoicePreset("female-shaonv", "Girl", gender = VoiceGender.Female),
        VoicePreset("female-yujie", "Elegant woman", gender = VoiceGender.Female),
        VoicePreset("male-qn-qingse", "Young man", gender = VoiceGender.Male),
        VoicePreset("male-qn-jingying", "Elite man", gender = VoiceGender.Male),
        VoicePreset("male-qn-badao", "Executive", gender = VoiceGender.Male),
        VoicePreset("Chinese (Mandarin)_News_Anchor", "News anchor", gender = VoiceGender.Female),
    )

    val miniMaxModels = listOf(
        VoicePreset("speech-2.8-turbo", "Speech 2.8 Turbo", "Faster"),
        VoicePreset("speech-2.8-hd", "Speech 2.8 HD", "Best quality"),
    )

    val mimo = listOf(
        VoicePreset("mimo_default", "Default"),
        VoicePreset("冰糖", "Rock Candy", "Chinese, female", gender = VoiceGender.Female),
        VoicePreset("茉莉", "Jasmine", "Chinese, female", gender = VoiceGender.Female),
        VoicePreset("苏打", "Soda", "Chinese, male", gender = VoiceGender.Male),
        VoicePreset("白桦", "Birch", "Chinese, male", gender = VoiceGender.Male),
        VoicePreset("Mia", "Mia", "English, female", gender = VoiceGender.Female),
        VoicePreset("Chloe", "Chloe", "English, female", gender = VoiceGender.Female),
        VoicePreset("Milo", "Milo", "English, male", gender = VoiceGender.Male),
        VoicePreset("Dean", "Dean", "English, male", gender = VoiceGender.Male),
    )

    val deepSeek = listOf(
        VoicePreset("stella", "Stella", "Female", gender = VoiceGender.Female),
        VoicePreset("starfish", "Starfish", "DeepSeek voice"),
        VoicePreset("tide", "Tide", "Male", gender = VoiceGender.Male),
    )

    fun presets(service: TtsServiceId): List<VoicePreset> = when (service) {
        TtsServiceId.Qwen -> qwen
        TtsServiceId.Doubao -> doubao
        TtsServiceId.Gemini -> gemini
        TtsServiceId.MiniMax -> miniMax
        TtsServiceId.Mimo -> mimo
        TtsServiceId.DeepSeek -> deepSeek
        TtsServiceId.Off, TtsServiceId.System -> emptyList()
    }

    /**
     * [presets] for the voice list: voices without a gender first (a service default), then "Female", then "Male".
     * Empty groups are left out.
     */
    fun grouped(service: TtsServiceId): List<Pair<VoiceGender?, List<VoicePreset>>> {
        val all = presets(service)
        return listOf(null, VoiceGender.Female, VoiceGender.Male)
            .map { kind -> kind to all.filter { it.gender == kind } }
            .filter { it.second.isNotEmpty() }
    }

    /** The preset a saved id belongs to (case-insensitive for Latin ids), or null for a custom voice id. */
    fun match(service: TtsServiceId, id: String): VoicePreset? {
        val trimmed = id.trim()
        return presets(service).firstOrNull { it.id == trimmed } ?: presets(service).firstOrNull { it.id.equals(trimmed, ignoreCase = true) }
    }
}
