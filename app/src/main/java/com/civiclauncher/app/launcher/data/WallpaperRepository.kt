package com.civiclauncher.app.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Fondos de pantalla. Viven en la carpeta de Civic (Android/data/com.civiclauncher.app/files/wallpapers):
 * se agregan desde una memoria USB en Ajustes, o se copian por adb desde la PC.
 *
 * La pantalla tiene 1 GB de RAM: nunca se carga una foto completa (una de 4K pesa ~40 MB en memoria).
 * El fondo se decodifica ya reducido al tamaño de la pantalla (~2.4 MB) y las miniaturas a 200 px.
 */
class WallpaperRepository(private val context: Context, private val scope: CoroutineScope) {
    val dir: File = (context.getExternalFilesDir("wallpapers") ?: File(context.filesDir, "wallpapers")).apply { mkdirs() }
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private val _files = MutableStateFlow<List<File>>(emptyList())
    /** Fondos disponibles, en orden (1, 2, … 10, 11). */
    val files: StateFlow<List<File>> = _files.asStateFlow()

    private val _selected = MutableStateFlow(sp.getString(KEY, null))
    /** Nombre del archivo elegido, o null = sin fondo (colores lisos). */
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _image = MutableStateFlow<ImageBitmap?>(null)
    /** El fondo ya listo para dibujar, o null mientras carga / sin fondo. */
    val image: StateFlow<ImageBitmap?> = _image.asStateFlow()

    private val _brightness = MutableStateFlow(0.3f)
    /** Qué tan claro es el fondo (0 = negro, 1 = blanco), para oscurecerlo lo justo y que el texto se lea. */
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    private val thumbs = HashMap<String, ImageBitmap>()
    private var loadJob: Job? = null

    init {
        refresh()
        loadSelected()
    }

    /** Volver a leer la carpeta (por si copiaste fondos mientras Civic estaba abierto). */
    fun refresh() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                dir.listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }.orEmpty()
                    .sortedWith(compareBy<File>({ it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }, { it.name }))
            }
            _files.value = list
            // Si borraron el fondo elegido, volver a colores lisos.
            val sel = _selected.value
            if (sel != null && list.none { it.name == sel }) select(null)
        }
    }

    fun select(name: String?) {
        _selected.value = name
        sp.edit().putString(KEY, name).apply()
        loadSelected()
    }

    private fun loadSelected() {
        loadJob?.cancel()
        val name = _selected.value
        if (name == null) {
            _image.value = null
            return
        }
        loadJob = scope.launch {
            val dm = context.resources.displayMetrics
            val bmp = withContext(Dispatchers.Default) { decodeCover(File(dir, name), dm.widthPixels, dm.heightPixels) }
            if (bmp != null) _brightness.value = withContext(Dispatchers.Default) { luminance(bmp) }
            _image.value = bmp?.asImageBitmap()
            if (bmp == null) select(null)
        }
    }

    /** Miniatura para Ajustes (~200 px). Se guarda en memoria para no volver a leer el archivo. */
    suspend fun thumbnail(file: File): ImageBitmap? {
        thumbs[file.name]?.let { return it }
        val t = withContext(Dispatchers.IO) { decodeCover(file, 200, 120)?.asImageBitmap() } ?: return null
        thumbs[file.name] = t
        return t
    }

    /**
     * Copia imágenes (de USB o galería) a la carpeta, ya reducidas, y elige la primera como fondo.
     * Devuelve cuántas se pudieron agregar.
     */
    fun import(uris: List<Uri>, onDone: (Int) -> Unit) {
        scope.launch {
            val names = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { i, uri ->
                    runCatching {
                        val bmp = decodeCover({ context.contentResolver.openInputStream(uri) }, IMPORT_W, IMPORT_H)
                            ?: error("No se pudo leer la imagen")
                        val out = File(dir, "fondo-${System.currentTimeMillis()}-$i.jpg")
                        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        bmp.recycle()
                        out.name
                    }.getOrNull()
                }
            }
            if (names.isNotEmpty()) {
                refresh()
                select(names.first())
            }
            onDone(names.size)
        }
    }

    fun delete(file: File) {
        thumbs.remove(file.name)
        runCatching { file.delete() }
        if (_selected.value == file.name) select(null)
        refresh()
    }

    /** Brillo promedio (luminancia) con una muestra de 16×16 píxeles: barato y suficiente. */
    private fun luminance(bmp: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bmp, 16, 16, true)
        var sum = 0f
        for (y in 0 until 16) for (x in 0 until 16) {
            val p = small.getPixel(x, y)
            sum += (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
        }
        if (small !== bmp) small.recycle()
        return sum / 256f
    }

    private fun decodeCover(file: File, w: Int, h: Int): Bitmap? = decodeCover({ file.inputStream() }, w, h)

    /**
     * Decodifica la imagen recortada al centro para llenar [w]×[h] (como "centerCrop"), leyendo solo
     * los píxeles necesarios: primero las medidas, luego con inSampleSize, y al final un escalado fino.
     */
    private fun decodeCover(open: () -> InputStream?, w: Int, h: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Reducir a la mitad mientras siga cubriendo el tamaño pedido en ambos lados.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val decoded = open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: return null
        val s = maxOf(w.toFloat() / decoded.width, h.toFloat() / decoded.height)
        val sw = (decoded.width * s).toInt().coerceAtLeast(w)
        val sh = (decoded.height * s).toInt().coerceAtLeast(h)
        val scaled = if (sw != decoded.width || sh != decoded.height) Bitmap.createScaledBitmap(decoded, sw, sh, true) else decoded
        if (scaled !== decoded) decoded.recycle()
        val cropped = Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h)
        if (cropped !== scaled) scaled.recycle()
        cropped
    }.getOrNull()

    private companion object {
        const val KEY = "wallpaper"
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        // Un poco más grande que la pantalla, por si algún día cambia de resolución.
        const val IMPORT_W = 1280
        const val IMPORT_H = 750
    }
}
