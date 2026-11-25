package `fun`.tryliner.android.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object LinerShapes {
    val Tile = RoundedCornerShape(6.dp)       // rounded-md for compact cover tiles
    val Control = RoundedCornerShape(8.dp)    // --radius-control (small controls, chips)
    val HeroButton = RoundedCornerShape(9.dp) // desktop hero button radius
    val HeroCover = RoundedCornerShape(10.dp) // desktop hero cover radius
    val Item = RoundedCornerShape(12.dp)      // --radius-item (rows, cards, fields, dialog buttons)
    val Floating = RoundedCornerShape(14.dp)  // --radius-2xl
    val BigArt = RoundedCornerShape(16.dp)    // --radius-3xl (big floating cover art)
    val Panel = RoundedCornerShape(20.dp)     // --radius-panel (dialogs, top-level containers)
    val Pill = CircleShape
}

val LinerMaterialShapes = Shapes(
    extraSmall = LinerShapes.Control,
    small = LinerShapes.Control,
    medium = LinerShapes.Item,
    large = LinerShapes.BigArt,
    extraLarge = LinerShapes.Panel
)
