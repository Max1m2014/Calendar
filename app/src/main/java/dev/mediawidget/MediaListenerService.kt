package dev.mediawidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService

/**
 * Слушатель уведомлений нужен только ради доступа к медиасессиям:
 * без него Android не даёт сторонним приложениям управлять чужими плеерами.
 */
class MediaListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private val tracked = mutableListOf<MediaController>()
    private var screenReceiverRegistered = false

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleRefresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = scheduleRefresh()
        override fun onSessionDestroyed() = scheduleRefresh()
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> track(list.orEmpty()) }

    private val refreshRunnable = Runnable {
        WidgetRenderer.updateAll(this)
        scheduleTick()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (shouldTick()) {
                WidgetRenderer.updateProgress(this@MediaListenerService)
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = scheduleRefresh()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val sm = getSystemService(MediaSessionManager::class.java)
        sessionManager = sm
        val cn = MediaHub.listenerComponent(this)
        try {
            sm.addOnActiveSessionsChangedListener(sessionsListener, cn, handler)
            track(sm.getActiveSessions(cn))
        } catch (_: SecurityException) {
        }
        if (!screenReceiverRegistered) {
            registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            screenReceiverRegistered = true
        }
    }

    override fun onListenerDisconnected() {
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        untrackAll()
        handler.removeCallbacksAndMessages(null)
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        WidgetRenderer.updateAll(this)
        super.onListenerDisconnected()
    }

    private fun track(list: List<MediaController>) {
        untrackAll()
        list.forEach {
            it.registerCallback(callback, handler)
            tracked += it
        }
        scheduleRefresh()
    }

    private fun untrackAll() {
        tracked.forEach { runCatching { it.unregisterCallback(callback) } }
        tracked.clear()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 120)
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        if (shouldTick()) handler.postDelayed(tick, 1000)
    }

    /** Двигаем прогресс раз в секунду, только когда экран включён и что-то играет. */
    private fun shouldTick(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        val c = MediaHub.pick(tracked) ?: return false
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return pm.isInteractive && dur > 0 && MediaHub.isPlaying(c) && WidgetRenderer.hasWidgets(this)
    }
}
