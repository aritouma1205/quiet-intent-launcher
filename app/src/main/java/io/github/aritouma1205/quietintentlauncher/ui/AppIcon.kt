package io.github.aritouma1205.quietintentlauncher.ui

import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App icon rendered from a lazily loaded [Drawable]. */
@Composable
fun DrawableIcon(
    modifier: Modifier = Modifier,
    contentDescription: String?,
    loader: suspend () -> Drawable?,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, loader) {
        value = withContext(Dispatchers.IO) { loader()?.toImageBitmap() }
    }
    bitmap?.let { Image(BitmapPainter(it), contentDescription, modifier) }
}

private fun Drawable.toImageBitmap(): ImageBitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap.asImageBitmap()
    val width = intrinsicWidth.coerceAtLeast(1)
    val height = intrinsicHeight.coerceAtLeast(1)
    val bmp = createBitmap(width, height)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp.asImageBitmap()
}
