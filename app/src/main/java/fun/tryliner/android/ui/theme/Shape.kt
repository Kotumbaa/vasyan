package `fun`.tryliner.android.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object LinerShapes {
    val Control = RoundedCornerShape(8.dp)
    val HeroButton = RoundedCornerShape(9.dp)
    val HeroCover = RoundedCornerShape(10.dp)
    val Item = RoundedCornerShape(12.dp)
    val BigArt = RoundedCornerShape(16.dp)
    val Panel = RoundedCornerShape(20.dp)
    val Pill = CircleShape
}

val LinerMaterialShapes = Shapes(
    extraSmall = LinerShapes.Control,
    small = LinerShapes.Control,
    medium = LinerShapes.Item,
    large = LinerShapes.BigArt,
    extraLarge = LinerShapes.Panel
)
