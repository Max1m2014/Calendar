package dev.mediawidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

class MediaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetRenderer.updateAll(context)
        MediaHub.requestRebind(context)
    }

    override fun onEnabled(context: Context) {
        MediaHub.requestRebind(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle
    ) {
        WidgetRenderer.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != ACTION_PLAY_PAUSE && action != ACTION_NEXT && action != ACTION_PREV) return

        val c = MediaHub.current(context)
        if (c != null) {
            val t = c.transportControls
            when (action) {
                ACTION_PLAY_PAUSE -> if (MediaHub.isPlaying(c)) t.pause() else t.play()
                ACTION_NEXT -> t.skipToNext()
                ACTION_PREV -> t.skipToPrevious()
            }
        } else {
            val key = when (action) {
                ACTION_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                ACTION_PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> KeyEvent.KEYCODE_MEDIA_PLAY
            }
            MediaHub.sendMediaKey(context, key)
        }

        // Сервис сам обновит виджет по колбэку, но на всякий случай — повторно через 0.5 с.
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { WidgetRenderer.updateAll(context) }
            pending.finish()
        }, 500)
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "dev.mediawidget.PLAY_PAUSE"
        const val ACTION_NEXT = "dev.mediawidget.NEXT"
        const val ACTION_PREV = "dev.mediawidget.PREV"
    }
}
