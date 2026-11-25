package atom.characters

import android.content.Context
import androidx.compose.runtime.Composable
import atom.characters.session.CharacterSession
import atom.characters.session.CharacterTools
import atom.characters.ui.CharacterRoutes
import atom.characters.ui.CharactersScreens
import atom.core.tool.Tool
import atom.ui.feature.AtomFeature
import atom.ui.feature.FeatureEntry
import atom.ui.feature.FeatureNav
import atom.ui.feature.FeatureSection

/** Character library, editor, worldbooks, persona and story memory; character chats run through [CharacterSession]. */
object CharactersFeature : AtomFeature {
    override val id: String = CharacterRoutes.FEATURE_ID

    override fun onAppCreate(context: Context) {
        CharacterSession.install(context)
    }

    override fun tools(context: Context): List<Tool> {
        CharacterSession.install(context)
        val tools = CharacterTools.create({ CharacterSession.store }, CharacterSession::activeCharacterIds)
        return if (CharacterSession.activeCharacterIds().isEmpty()) tools.filterNot { it.spec.name == CharacterSession.STORY_MEMORY_TOOL } else tools
    }

    override fun promptHint(context: Context): String? {
        if (CharacterSession.activeCharacterIds().isEmpty()) return null
        return "Character chats: stay in character, keep using real tools when asked, and record story facts with " +
            "story_memory_update; MEMORY.md is read-only there."
    }

    override val entries: List<FeatureEntry>
        get() = listOf(
            FeatureEntry(
                section = FeatureSection.Context,
                title = R.string.characters_entry_title,
                subtitle = R.string.characters_entry_subtitle,
                icon = CharactersScreens.EntryIcon,
                route = CharacterRoutes.LIBRARY,
            ),
        )

    @Composable
    override fun Screen(route: String, nav: FeatureNav) {
        CharactersScreens.Render(route, nav)
    }
}
