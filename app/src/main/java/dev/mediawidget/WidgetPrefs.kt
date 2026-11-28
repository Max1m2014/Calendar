package dev.mediawidget

import android.content.Context

/** Варианты фона виджета. */
enum class BgMode {
    /** Размытая обложка, цвет подстраивается под альбом */
    ADAPTIVE,
    /** Всегда тёмный */
    DARK,
    /** Светлый днём / тёмный ночью — как тема телефона */
    SYSTEM,
    /** Чисто чёрный для AMOLED */
    AMOLED
}

object WidgetPrefs {
    private const val FILE = "settings"
    private const val KEY_BG = "bg_mode"

    fun bgMode(ctx: Context): BgMode {
        val v = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_BG, null)
        return BgMode.entries.firstOrNull { it.name == v } ?: BgMode.ADAPTIVE
    }

    fun setBgMode(ctx: Context, mode: BgMode) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_BG, mode.name).apply()
    }
}
