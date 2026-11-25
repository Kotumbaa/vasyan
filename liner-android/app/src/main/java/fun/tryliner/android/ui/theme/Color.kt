package `fun`.tryliner.android.ui.theme

import androidx.compose.ui.graphics.Color

object LinerColors {
    // Atmospheric dark theme tokens (matching desktop DESIGN.md + #0D0F14 / #101218 base)
    val BgCanvas = Color(0xFF0D0F14)
    val AmbientBase = Color(0xFF0D0F14)
    val AmbientBlue = Color(0xFF1D273B)
    val AmbientCrimson = Color(0xFF281D2B)
    val AmbientViolet = Color(0xFF231D36)
    val BgPrimary = Color(0xFF0D0F14)
    val BgElevated = Color(0xFF14171F)
    val BgPanel = Color(0xFF181C26)
    val SurfaceGlass = Color(0xEB101218)    // rgba(16, 18, 24, 0.92)
    val NavBarGlass = Color(0xE60D0F14)     // dense translucent slate-dark scrim
    val MiniPlayerGlass = Color(0xC714161C) // rgba(20, 22, 28, 0.78)

    val TextPrimary = Color(0xF2FFFFFF)     // white @ 95%
    val TextSecondary = Color(0x99FFFFFF)   // white @ 60%
    val TextTertiary = Color(0x73FFFFFF)    // white @ 45%

    val AccentPrimary = Color(0xF2FFFFFF)   // monochrome white active chrome
    val AccentRed = Color(0xF2FFFFFF)       // monochrome liked/active accent (no loud red)
    val AccentSecondary = Color(0xFFD0D5DD)
    val AccentWarning = Color(0xFFE5C07B)

    val BtnPrimaryBg = Color(0xFFFFFFFF)
    val BtnPrimaryText = Color(0xFF0D0F14)

    val FillSubtle = Color(0x0DFFFFFF)      // white @ 5%
    val Fill = Color(0x12FFFFFF)            // white @ 7%
    val FillHover = Color(0x1AFFFFFF)       // white @ 10%
    val FillStrong = Color(0x24FFFFFF)      // white @ 14%
    val HeroGlass = Color(0x1AFFFFFF)       // white @ 10%

    val BorderAlpha14 = Color(0x0DFFFFFF)   // white @ 5% (near-invisible if ever needed)
    val BorderAlpha20 = Color(0x0DFFFFFF)   // white @ 5%
    val BorderAlpha33 = Color(0x14FFFFFF)   // white @ 8%

    val LoginBackdrop = Color(0xFF101218)
    val LoginGlow = Color(0xFF3E4663)
    val LoginGlowSecondary = Color(0xFF2B324A)

    val LikedVioletStart = Color(0xFF5A42D4)
    val LikedVioletEnd = Color(0xFF241848)
}
