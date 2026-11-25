package atom.voice.speech

/** One preset voice: the id sent to the service and the name shown to the user. */
data class VoicePreset(val id: String, val name: String, val note: String? = null)

/** Preset voices per spoken-answer service, plus matching a saved id back to its preset. */
object VoiceCatalog {
    val qwen = listOf(
        VoicePreset("Cherry", "Cherry"),
        VoicePreset("Serena", "Serena"),
        VoicePreset("Ethan", "Ethan"),
        VoicePreset("Chelsie", "Chelsie"),
        VoicePreset("Momo", "Momo"),
        VoicePreset("Vivian", "Vivian"),
        VoicePreset("Moon", "Moon"),
        VoicePreset("Maia", "Maia"),
        VoicePreset("Kai", "Kai"),
        VoicePreset("Nofish", "Nofish"),
        VoicePreset("Bella", "Bella"),
        VoicePreset("Katerina", "Katerina"),
        VoicePreset("Vincent", "Vincent"),
        VoicePreset("Neil", "Neil"),
        VoicePreset("Jennifer", "Jennifer", "American English"),
        VoicePreset("Ryan", "Ryan"),
        VoicePreset("Dylan", "Dylan", "Beijing dialect, male"),
        VoicePreset("Jada", "Jada", "Shanghai dialect, female"),
        VoicePreset("Sunny", "Sunny", "Sichuan dialect, female"),
        VoicePreset("Rocky", "Rocky", "Cantonese, male"),
        VoicePreset("Kiki", "Kiki", "Cantonese, female"),
    )

    val doubao = listOf(
        VoicePreset("zh_female_vv_uranus_bigtts", "Vivi"),
        VoicePreset("zh_female_xiaohe_uranus_bigtts", "Xiaohe"),
        VoicePreset("zh_male_m191_uranus_bigtts", "Yunzhou"),
        VoicePreset("zh_male_taocheng_uranus_bigtts", "Xiaotian"),
        VoicePreset("zh_male_liufei_uranus_bigtts", "Liufei"),
        VoicePreset("zh_female_qingxinnvsheng_uranus_bigtts", "Fresh Voice"),
        VoicePreset("zh_female_cancan_uranus_bigtts", "Cancan"),
        VoicePreset("zh_female_tianmeixiaoyuan_uranus_bigtts", "Xiaoyuan"),
        VoicePreset("zh_female_tianmeitaozi_uranus_bigtts", "Taozi"),
        VoicePreset("zh_female_shuangkuaisisi_uranus_bigtts", "Sisi"),
        VoicePreset("zh_female_linjianvhai_uranus_bigtts", "Girl Next Door"),
        VoicePreset("zh_male_shaonianzixin_uranus_bigtts", "Zixin"),
        VoicePreset("zh_male_wennuanahu_uranus_bigtts", "Ahu"),
        VoicePreset("zh_male_ruyayichen_uranus_bigtts", "Yichen"),
        VoicePreset("zh_male_jieshuoxiaoming_uranus_bigtts", "Xiaoming"),
        VoicePreset("zh_male_shenyeboke_uranus_bigtts", "Late Night Podcast"),
    )

    val gemini = listOf(
        VoicePreset("Kore", "Kore", "Firm"),
        VoicePreset("Puck", "Puck", "Upbeat"),
        VoicePreset("Charon", "Charon", "Informative"),
        VoicePreset("Aoede", "Aoede", "Breezy"),
        VoicePreset("Zephyr", "Zephyr", "Bright"),
        VoicePreset("Leda", "Leda", "Youthful"),
        VoicePreset("Sulafat", "Sulafat", "Warm"),
        VoicePreset("Achernar", "Achernar", "Soft"),
    )

    val geminiModels = listOf(
        VoicePreset(GeminiTtsModels.FLASH, "Gemini 3.8 Flash", "Best quality"),
        VoicePreset(GeminiTtsModels.FLASH_LITE, "Gemini 3.8 Flash-Lite", "Lower latency"),
    )

    val miniMax = listOf(
        VoicePreset("female-chengshu", "Mature woman"),
        VoicePreset("female-tianmei", "Sweet woman"),
        VoicePreset("female-shaonv", "Girl"),
        VoicePreset("female-yujie", "Elegant woman"),
        VoicePreset("male-qn-qingse", "Young man"),
        VoicePreset("male-qn-jingying", "Elite man"),
        VoicePreset("male-qn-badao", "Executive"),
        VoicePreset("Chinese (Mandarin)_News_Anchor", "News anchor"),
    )

    val miniMaxModels = listOf(
        VoicePreset("speech-2.8-turbo", "Speech 2.8 Turbo", "Faster"),
        VoicePreset("speech-2.8-hd", "Speech 2.8 HD", "Best quality"),
    )

    val mimo = listOf(
        VoicePreset("mimo_default", "Default"),
        VoicePreset("冰糖", "Rock Candy", "Chinese, female"),
        VoicePreset("茉莉", "Jasmine", "Chinese, female"),
        VoicePreset("苏打", "Soda", "Chinese, male"),
        VoicePreset("白桦", "Birch", "Chinese, male"),
        VoicePreset("Mia", "Mia", "English, female"),
        VoicePreset("Chloe", "Chloe", "English, female"),
        VoicePreset("Milo", "Milo", "English, male"),
        VoicePreset("Dean", "Dean", "English, male"),
    )

    val deepSeek = listOf(
        VoicePreset("stella", "Stella", "Female"),
        VoicePreset("tide", "Tide", "Male"),
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

    /** The preset a saved id belongs to (case-insensitive for Latin ids), or null for a custom voice id. */
    fun match(service: TtsServiceId, id: String): VoicePreset? {
        val trimmed = id.trim()
        return presets(service).firstOrNull { it.id == trimmed } ?: presets(service).firstOrNull { it.id.equals(trimmed, ignoreCase = true) }
    }
}
