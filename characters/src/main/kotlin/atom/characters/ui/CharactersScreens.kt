package atom.characters.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import atom.ui.feature.FeatureNav

object CharactersScreens {
    val EntryIcon: ImageVector get() = Icons.Filled.Face

    internal val ImportIcon: ImageVector by lazy {
        icon("Import", "M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z")
    }

    internal val FavouriteOutline: ImageVector by lazy {
        icon(
            "FavouriteOutline",
            "M16.5,3c-1.74,0 -3.41,0.81 -4.5,2.09C10.91,3.81 9.24,3 7.5,3 4.42,3 2,5.42 2,8.5c0,3.78 3.4,6.86 8.55,11.54L12,21.35l1.45,-1.32C18.6,15.36 22,12.28 22,8.5 22,5.42 19.58,3 16.5,3zM12.1,18.55l-0.1,0.1 -0.1,-0.1C7.14,14.24 4,11.39 4,8.5 4,6.5 5.5,5 7.5,5c1.54,0 3.04,0.99 3.57,2.36h1.87C13.46,5.99 14.96,5 16.5,5c2,0 3.5,1.5 3.5,3.5 0,2.89 -3.14,5.74 -7.9,10.05z",
        )
    }

    @Composable
    fun Render(route: String, nav: FeatureNav) {
        when (val r = CharacterRoutes.parse(route)) {
            CharacterRoutes.Parsed.Library -> LibraryScreen(nav)
            CharacterRoutes.Parsed.Persona -> PersonaScreen(nav)
            is CharacterRoutes.Parsed.Detail -> DetailScreen(r.id, nav)
            is CharacterRoutes.Parsed.Edit -> EditorScreen(r.id, nav)
            is CharacterRoutes.Parsed.WorldbookList -> WorldbookScreen(r.id, nav)
            is CharacterRoutes.Parsed.Entry -> EntryScreen(r.id, r.entryId, nav)
            is CharacterRoutes.Parsed.Story -> StoryMemoryScreen(r.id, nav)
            null -> LibraryScreen(nav)
        }
    }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
