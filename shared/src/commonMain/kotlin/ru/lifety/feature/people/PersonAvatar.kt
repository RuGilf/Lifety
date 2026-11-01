package ru.lifety.feature.people

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

@Composable
internal expect fun rememberPersonPhotoPicker(onPicked: (String) -> Unit, onFinished: () -> Unit, onError: (String) -> Unit): () -> Unit
internal expect fun decodePersonAvatar(bytes: ByteArray): ImageBitmap?

@Composable
internal fun PersonAvatar(name: String, photo: String?, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val bitmapState = key(remember(photo) { Any() }) {
        produceState<ImageBitmap?>(null) {
            value = withContext(Dispatchers.Default) { photo?.let { runCatching { decodePersonAvatar(Base64.Default.decode(it)) }.getOrNull() } }
        }
    }
    val bitmap by bitmapState
    val initials = remember(name) { name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("") }
    Surface(modifier.size(size), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(requireNotNull(bitmap), contentDescription = "Фото: ${name.ifBlank { "человек" }}", contentScale = ContentScale.Crop)
            else Text(initials.ifEmpty { "+" }, style = if (size > 64.dp) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleMedium)
        }
    }
}
