package io.github.aritouma1205.quietintentlauncher.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.aritouma1205.quietintentlauncher.R
import io.github.aritouma1205.quietintentlauncher.settings.ActionIcon

/**
 * Maps the persisted [ActionIcon] identifiers to the small set of core
 * Material icons bundled with Compose. The auxiliary icon is decorative —
 * the action name stays the primary element (design 6).
 */
fun ActionIcon.imageVector(): ImageVector = when (this) {
    ActionIcon.Star -> Icons.Filled.Star
    ActionIcon.Face -> Icons.Filled.Face
    ActionIcon.Call -> Icons.Filled.Call
    ActionIcon.Play -> Icons.Filled.PlayArrow
    ActionIcon.Place -> Icons.Filled.Place
    ActionIcon.Search -> Icons.Filled.Search
    ActionIcon.Pen -> Icons.Filled.Create
    ActionIcon.Heart -> Icons.Filled.Favorite
}

@get:StringRes
val ActionIcon.labelRes: Int
    get() = when (this) {
        ActionIcon.Star -> R.string.icon_star
        ActionIcon.Face -> R.string.icon_face
        ActionIcon.Call -> R.string.icon_call
        ActionIcon.Play -> R.string.icon_play
        ActionIcon.Place -> R.string.icon_place
        ActionIcon.Search -> R.string.icon_search
        ActionIcon.Pen -> R.string.icon_pen
        ActionIcon.Heart -> R.string.icon_heart
    }
