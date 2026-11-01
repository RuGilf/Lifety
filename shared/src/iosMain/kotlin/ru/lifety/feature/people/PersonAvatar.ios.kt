@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ru.lifety.feature.people

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.uikit.LocalUIViewController
import ru.lifety.core.platform.IosPersonPhotoRequest
import ru.lifety.core.platform.IosServices
import org.jetbrains.skia.Image

@Composable
internal actual fun rememberPersonPhotoPicker(onPicked: (String) -> Unit, onFinished: () -> Unit, onError: (String) -> Unit): () -> Unit {
    val host = LocalUIViewController.current
    val picked by rememberUpdatedState(onPicked)
    val finished by rememberUpdatedState(onFinished)
    val failed by rememberUpdatedState(onError)
    val holder = remember { object { var request: IosPersonPhotoRequest? = null; var active = true } }
    DisposableEffect(holder) { holder.active = true; onDispose { holder.active = false; holder.request?.cancel(); holder.request = null } }
    return remember(host) { {
        holder.request?.cancel()
        val service = IosServices.personPhotos
        if (service == null) { failed("Выбор фото недоступен."); finished() }
        else holder.request = service.pick(host) { image, error ->
            if (holder.active) {
                if (image != null) picked(image)
                if (error != null) failed(error)
                finished()
                holder.request = null
            }
        }
    } }
}

internal actual fun decodePersonAvatar(bytes: ByteArray): ImageBitmap? {
    val image = Image.makeFromEncoded(bytes)
    if (image.width !in 1..512 || image.height !in 1..512) { image.close(); return null }
    return image.toComposeImageBitmap()
}
