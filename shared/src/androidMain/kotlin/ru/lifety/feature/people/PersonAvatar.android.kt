package ru.lifety.feature.people

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import ru.lifety.core.model.MAX_PERSON_AVATAR_BYTES
import java.io.ByteArrayOutputStream

@Composable
internal actual fun rememberPersonPhotoPicker(onPicked: (String) -> Unit, onFinished: () -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val finished by rememberUpdatedState(onFinished)
    val failed by rememberUpdatedState(onError)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) finished() else scope.launch {
            try { picked(withContext(Dispatchers.IO) { avatarFromUri(context, uri) }) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed("Не удалось загрузить фото. Выберите другое изображение.") }
            finally { finished() }
        }
    }
    return { try { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: Exception) { failed("Выбор фото недоступен на этом устройстве."); finished() } }
}

private fun avatarFromUri(context: Context, uri: Uri): String {
    val resolver = context.contentResolver
    val bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, 1024.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1024) inSampleSize *= 2
        }
        val source = requireNotNull(resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) })
        val orientation = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull()
        val matrix = Matrix().apply { when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        } }
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also { if (it !== source) source.recycle() }
    }
    try {
        val square = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            val side = minOf(bitmap.width, bitmap.height)
            val x = (bitmap.width - side) / 2; val y = (bitmap.height - side) / 2
            Canvas(square).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, Rect(x, y, x + side, y + side), Rect(0, 0, 512, 512), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            for (quality in listOf(82, 65, 45)) {
                val output = ByteArrayOutputStream()
                require(square.compress(Bitmap.CompressFormat.JPEG, quality, output))
                if (output.size() <= MAX_PERSON_AVATAR_BYTES) return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
            error("Image too large")
        } finally { square.recycle() }
    } finally { bitmap.recycle() }
}

internal actual fun decodePersonAvatar(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
