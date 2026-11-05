package com.civiclauncher.app.diag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Música de prueba (sin sonido): publica una "sesión de música" falsa con canciones inventadas
 * y portadas generadas. Sirve para ver la tarjeta de música en el emulador o sin el celular.
 */
class DemoMediaSession(private val context: Context) {
    private data class Track(val title: String, val artist: String, val album: String, val durationMs: Long, val c1: Int, val c2: Int)

    private val tracks = listOf(
        Track("Carretera de noche", "Los Ejemplos", "Kilómetro cero", 214_000, 0xFF3B8BFF.toInt(), 0xFF7A3BFF.toInt()),
        Track("Atardecer en la costa", "Banda de Prueba", "Ruta 15", 188_000, 0xFFFF8A3B.toInt(), 0xFFE0392E.toInt()),
        Track("Semáforo en verde", "Simulador FM", "Ciudad", 241_000, 0xFF2FD27A.toInt(), 0xFF0E8FCC.toInt()),
    )

    private var session: MediaSession? = null
    private var index = 0
    private var playing = true
    private var position = 0L
    private var positionAt = SystemClock.elapsedRealtime()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun start() {
        if (session != null) return
        val s = MediaSession(context, "civic-demo")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { setPlaying(true) }
            override fun onPause() { setPlaying(false) }
            override fun onSkipToNext() { change(+1) }
            override fun onSkipToPrevious() { change(-1) }
        })
        @Suppress("DEPRECATION")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        s.isActive = true
        session = s
        _active.value = true
        publish()
    }

    fun stop() {
        session?.release()
        session = null
        _active.value = false
    }

    private fun currentPosition(): Long =
        if (playing) position + (SystemClock.elapsedRealtime() - positionAt) else position

    private fun setPlaying(p: Boolean) {
        position = currentPosition()
        positionAt = SystemClock.elapsedRealtime()
        playing = p
        publish()
    }

    private fun change(delta: Int) {
        index = (index + delta + tracks.size) % tracks.size
        position = 0
        positionAt = SystemClock.elapsedRealtime()
        publish()
    }

    private fun publish() {
        val s = session ?: return
        val t = tracks[index]
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover(t))
                .build(),
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, currentPosition(), 1f, SystemClock.elapsedRealtime())
                .build(),
        )
    }

    private fun cover(t: Track): Bitmap {
        val size = 300
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), t.c1, t.c2, Shader.TileMode.CLAMP) }
        Canvas(bmp).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return bmp
    }
}
