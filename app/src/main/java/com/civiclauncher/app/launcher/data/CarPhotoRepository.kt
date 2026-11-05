package com.civiclauncher.app.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * La foto de TU carro para la tarjeta "Mi vehículo". La eliges tú desde la galería o una memoria USB;
 * se guarda reducida (máx. 640 px) para no gastar memoria.
 */
class CarPhotoRepository(private val context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "car_photo.png")
    private val _photo = MutableStateFlow<ImageBitmap?>(null)
    val photo: StateFlow<ImageBitmap?> = _photo.asStateFlow()

    init {
        scope.launch { _photo.value = withContext(Dispatchers.IO) { load() } }
    }

    private fun load(): ImageBitmap? = runCatching {
        if (!file.exists()) null else BitmapFactory.decodeFile(file.path)?.asImageBitmap()
    }.getOrNull()

    fun setFrom(uri: Uri, onDone: (Boolean) -> Unit) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // Primero solo las medidas, para no cargar en memoria una foto de 12 MP completa.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= MAX && bounds.outHeight / (sample * 2) >= MAX / 2) sample *= 2
                    val decoded = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: error("No se pudo leer la imagen")
                    val scale = MAX.toFloat() / maxOf(decoded.width, decoded.height)
                    val final = if (scale < 1f) {
                        Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
                    } else {
                        decoded
                    }
                    file.outputStream().use { final.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    _photo.value = final.asImageBitmap()
                    true
                }.getOrDefault(false)
            }
            onDone(ok)
        }
    }

    fun clear() {
        runCatching { file.delete() }
        _photo.value = null
    }

    private companion object {
        const val MAX = 640
    }
}
