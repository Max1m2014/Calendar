package dev.mediawidget

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.view.KeyEvent

/** Доступ к активным медиасессиям (Spotify, YouTube, YT Music, VK, Telegram и т.д.). */
object MediaHub {

    fun listenerComponent(ctx: Context) = ComponentName(ctx, MediaListenerService::class.java)

    fun hasAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(listenerComponent(ctx))

    fun requestRebind(ctx: Context) {
        if (!hasAccess(ctx)) return
        runCatching { NotificationListenerService.requestRebind(listenerComponent(ctx)) }
    }

    fun controllers(ctx: Context): List<MediaController> {
        if (!hasAccess(ctx)) return emptyList()
        return try {
            ctx.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(listenerComponent(ctx))
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Играющая сессия, иначе первая с метаданными (список уже отсортирован системой по приоритету). */
    fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { isPlaying(it) }
            ?: list.firstOrNull { it.metadata != null }
            ?: list.firstOrNull()

    fun current(ctx: Context): MediaController? = pick(controllers(ctx))

    fun isPlaying(c: MediaController?): Boolean {
        val s = c?.playbackState?.state ?: return false
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
    }

    fun position(c: MediaController, duration: Long): Long {
        val st = c.playbackState ?: return 0L
        var p = st.position
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            p += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        return p.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
    }

    /** Если активной сессии нет — шлём медиакнопку: система продолжит последний плеер. */
    fun sendMediaKey(ctx: Context, keyCode: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
