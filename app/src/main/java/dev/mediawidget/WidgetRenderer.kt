package dev.mediawidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.util.Size
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import kotlin.math.min

object WidgetRenderer {

    // Кэш, чтобы не пересчитывать обложку и фон при каждом обновлении
    private var cacheKey: String? = null
    private var cachedArt: Bitmap? = null
    private var cachedBg: Bitmap? = null
    private var cachedIconPkg: String? = null
    private var cachedIcon: Bitmap? = null

    private fun ids(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(ComponentName(ctx, MediaWidgetProvider::class.java))

    fun hasWidgets(ctx: Context) = ids(ctx).isNotEmpty()

    /** Уже́ этого порога (≈ 2–3 ячейки) — квадратный вид: обложка на весь виджет. */
    private const val SQUARE_MAX_WIDTH_DP = 200

    /** Выбор макета по текущему размеру конкретного виджета. */
    private fun layoutFor(mgr: AppWidgetManager, id: Int): Int {
        val o = mgr.getAppWidgetOptions(id)
        @Suppress("DEPRECATION")
        val sizes = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        val w = sizes?.minOfOrNull { it.width.toInt() }
            ?: o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        return if (w in 1 until SQUARE_MAX_WIDTH_DP) R.layout.widget_media_square
        else R.layout.widget_media
    }

    fun updateAll(ctx: Context) {
        val ids = ids(ctx)
        if (ids.isEmpty()) return
        val mgr = AppWidgetManager.getInstance(ctx)
        val built = HashMap<Int, RemoteViews>()
        for (id in ids) {
            val layout = layoutFor(mgr, id)
            val rv = built.getOrPut(layout) { build(ctx, layout) }
            mgr.updateAppWidget(id, rv)
        }
    }

    /** Лёгкое ежесекундное обновление: только полоска и время. */
    fun updateProgress(ctx: Context) {
        val ids = ids(ctx)
        if (ids.isEmpty()) return
        val c = MediaHub.current(ctx) ?: return
        val mgr = AppWidgetManager.getInstance(ctx)
        for (id in ids) {
            val rv = RemoteViews(ctx.packageName, layoutFor(mgr, id))
            applyProgress(rv, c)
            mgr.partiallyUpdateAppWidget(id, rv)
        }
    }

    private fun build(ctx: Context, layout: Int): RemoteViews {
        val rv = RemoteViews(ctx.packageName, layout)
        // В квадратном виде фон — сама обложка, поэтому там всегда светлые элементы на затемнении
        val mode = if (layout == R.layout.widget_media_square) BgMode.DARK else WidgetPrefs.bgMode(ctx)
        rv.setOnClickPendingIntent(R.id.prev, broadcast(ctx, MediaWidgetProvider.ACTION_PREV, 1))
        rv.setOnClickPendingIntent(R.id.play, broadcast(ctx, MediaWidgetProvider.ACTION_PLAY_PAUSE, 2))
        rv.setOnClickPendingIntent(R.id.next, broadcast(ctx, MediaWidgetProvider.ACTION_NEXT, 3))

        val openMain = PendingIntent.getActivity(
            ctx, 10,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (!MediaHub.hasAccess(ctx)) {
            showEmpty(rv, ctx, R.string.need_access, R.string.need_access_sub)
            applyColors(rv, mode, hasArt = false, hasAppIcon = false)
            rv.setOnClickPendingIntent(R.id.art, openMain)
            rv.setOnClickPendingIntent(R.id.info, openMain)
            rv.setOnClickPendingIntent(R.id.play, openMain)
            return rv
        }

        val c = MediaHub.current(ctx)
        val md = c?.metadata
        if (c == null || md == null) {
            showEmpty(rv, ctx, R.string.nothing_playing, R.string.nothing_playing_sub)
            applyColors(rv, mode, hasArt = false, hasAppIcon = false)
            rv.setOnClickPendingIntent(R.id.art, openMain)
            rv.setOnClickPendingIntent(R.id.info, openMain)
            return rv
        }

        val pkg = c.packageName
        val label = appLabel(ctx, pkg)
        val title = md.getText(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: label
        val artist = md.getText(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: md.getText(MediaMetadata.METADATA_KEY_ALBUM)
            ?: ""

        rv.setTextViewText(R.id.title, title)
        rv.setTextViewText(R.id.artist, artist)
        rv.setViewVisibility(R.id.artist, if (artist.isBlank()) View.GONE else View.VISIBLE)
        rv.setTextViewText(R.id.app_name, label)
        val icon = appIcon(ctx, pkg)
        if (icon != null) rv.setImageViewBitmap(R.id.app_icon, icon)
        else rv.setImageViewResource(R.id.app_icon, R.drawable.ic_note)

        // Обложка (музыка) или превью (YouTube, видеоплееры передают кадр как ART)
        val raw = loadArt(ctx, md)
        val key = "$pkg|$title|$artist|${raw?.generationId}|${raw?.width}"
        if (key != cacheKey) {
            cacheKey = key
            val sw = raw?.let { software(it) }
            cachedArt = sw?.let { square(it, 480) }
            cachedBg = sw?.let { backdrop(it) }
        }
        val art = cachedArt
        if (art != null) rv.setImageViewBitmap(R.id.art, art)
        else rv.setImageViewResource(R.id.art, R.drawable.ic_art_placeholder)

        val bg = cachedBg
        if (bg != null && mode == BgMode.ADAPTIVE) {
            rv.setViewVisibility(R.id.bg_image, View.VISIBLE)
            rv.setImageViewBitmap(R.id.bg_image, bg)
        } else {
            rv.setViewVisibility(R.id.bg_image, View.GONE)
        }

        // Тап по обложке/тексту открывает плеер
        val open = launchIntent(ctx, pkg) ?: c.sessionActivity
        if (open != null) {
            rv.setOnClickPendingIntent(R.id.art, open)
            rv.setOnClickPendingIntent(R.id.info, open)
        }

        val playing = MediaHub.isPlaying(c)
        rv.setImageViewResource(R.id.play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        rv.setContentDescription(
            R.id.play, ctx.getString(if (playing) R.string.cd_pause else R.string.cd_play)
        )

        val actions = c.playbackState?.actions ?: 0L
        rv.setInt(R.id.next, "setImageAlpha",
            if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) 255 else 100)
        rv.setInt(R.id.prev, "setImageAlpha",
            if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) 255 else 100)

        applyColors(rv, mode, hasArt = art != null, hasAppIcon = icon != null)
        applyProgress(rv, c)
        return rv
    }

    // ---------- Цвета фона и элементов ----------

    private data class Pal(
        val card: Int, val text: Int, val text2: Int, val icon: Int,
        val playBg: Int, val playIcon: Int, val track: Int, val placeholderBg: Int
    )

    private const val WHITE = 0xFFFFFFFF.toInt()
    private val DARK = Pal(
        card = 0xFF26262B.toInt(), text = WHITE, text2 = 0xBFFFFFFF.toInt(), icon = WHITE,
        playBg = WHITE, playIcon = 0xFF111114.toInt(), track = 0x4DFFFFFF, placeholderBg = 0xFF3A3A42.toInt()
    )
    private val AMOLED = DARK.copy(card = 0xFF000000.toInt(), placeholderBg = 0xFF1E1E22.toInt())
    private val LIGHT = Pal(
        card = 0xFFF7F7FA.toInt(), text = 0xFF111114.toInt(), text2 = 0x99000000.toInt(),
        icon = 0xFF111114.toInt(), playBg = 0xFF111114.toInt(), playIcon = WHITE,
        track = 0x26000000, placeholderBg = 0xFFE4E4E8.toInt()
    )

    /**
     * Для режима «как в системе» передаём две палитры: launcher сам выберет
     * дневную или ночную при отрисовке, так что виджет меняется вместе с темой телефона.
     */
    private fun applyColors(rv: RemoteViews, mode: BgMode, hasArt: Boolean, hasAppIcon: Boolean) {
        val (day, night) = when (mode) {
            BgMode.ADAPTIVE, BgMode.DARK -> DARK to DARK
            BgMode.AMOLED -> AMOLED to AMOLED
            BgMode.SYSTEM -> LIGHT to DARK
        }
        fun csl(c: Int) = ColorStateList.valueOf(c)
        fun tint(id: Int, method: String, pick: (Pal) -> Int) =
            rv.setColorStateList(id, method, csl(pick(day)), csl(pick(night)))
        fun color(id: Int, method: String, pick: (Pal) -> Int) =
            rv.setColorInt(id, method, pick(day), pick(night))

        tint(R.id.card, "setBackgroundTintList") { it.card }
        color(R.id.title, "setTextColor") { it.text }
        for (id in intArrayOf(R.id.artist, R.id.app_name, R.id.elapsed, R.id.duration)) {
            color(id, "setTextColor") { it.text2 }
        }
        color(R.id.prev, "setColorFilter") { it.icon }
        color(R.id.next, "setColorFilter") { it.icon }
        tint(R.id.play, "setBackgroundTintList") { it.playBg }
        color(R.id.play, "setColorFilter") { it.playIcon }
        tint(R.id.progress, "setProgressTintList") { it.text }
        tint(R.id.progress, "setProgressBackgroundTintList") { it.track }

        if (hasArt) {
            rv.setInt(R.id.art, "setColorFilter", 0)
        } else {
            tint(R.id.art, "setBackgroundTintList") { it.placeholderBg }
            color(R.id.art, "setColorFilter") { it.text2 }
        }
        if (hasAppIcon) rv.setInt(R.id.app_icon, "setColorFilter", 0)
        else color(R.id.app_icon, "setColorFilter") { it.text2 }
    }

    private fun showEmpty(rv: RemoteViews, ctx: Context, titleRes: Int, subRes: Int) {
        rv.setTextViewText(R.id.title, ctx.getString(titleRes))
        rv.setTextViewText(R.id.artist, ctx.getString(subRes))
        rv.setViewVisibility(R.id.artist, View.VISIBLE)
        rv.setTextViewText(R.id.app_name, ctx.getString(R.string.widget_name))
        rv.setImageViewResource(R.id.app_icon, R.drawable.ic_note)
        rv.setImageViewResource(R.id.art, R.drawable.ic_art_placeholder)
        rv.setViewVisibility(R.id.bg_image, View.GONE)
        rv.setViewVisibility(R.id.progress_group, View.INVISIBLE)
        rv.setImageViewResource(R.id.play, R.drawable.ic_play)
        rv.setInt(R.id.next, "setImageAlpha", 255)
        rv.setInt(R.id.prev, "setImageAlpha", 255)
    }

    private fun applyProgress(rv: RemoteViews, c: MediaController) {
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (dur <= 0) {
            rv.setViewVisibility(R.id.progress_group, View.INVISIBLE)
            return
        }
        val pos = MediaHub.position(c, dur)
        rv.setViewVisibility(R.id.progress_group, View.VISIBLE)
        rv.setProgressBar(R.id.progress, 1000, (pos * 1000 / dur).toInt(), false)
        rv.setTextViewText(R.id.elapsed, fmt(pos))
        rv.setTextViewText(R.id.duration, fmt(dur))
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun broadcast(ctx: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, MediaWidgetProvider::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun launchIntent(ctx: Context, pkg: String): PendingIntent? {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            ctx, 20, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun appIcon(ctx: Context, pkg: String): Bitmap? {
        if (pkg == cachedIconPkg) return cachedIcon
        val bmp = try {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val b = Bitmap.createBitmap(56, 56, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 56, 56)
            d.draw(Canvas(b))
            b
        } catch (_: Exception) {
            null
        }
        cachedIconPkg = pkg
        cachedIcon = bmp
        return bmp
    }

    private fun loadArt(ctx: Context, md: MediaMetadata): Bitmap? {
        md.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return it }
        md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return it }
        md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return it }
        val keys = listOf(
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        )
        for (k in keys) {
            val s = md.getString(k) ?: continue
            val uri = Uri.parse(s)
            if (uri.scheme != ContentResolver.SCHEME_CONTENT &&
                uri.scheme != ContentResolver.SCHEME_ANDROID_RESOURCE &&
                uri.scheme != ContentResolver.SCHEME_FILE
            ) continue
            try {
                return ctx.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun software(b: Bitmap): Bitmap =
        if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b

    private fun square(src: Bitmap, size: Int): Bitmap {
        val s = min(src.width, src.height)
        val crop = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
        return Bitmap.createScaledBitmap(crop, size, size, true)
    }

    /** Фон как в One UI: сильно размытая и затемнённая обложка. */
    private fun backdrop(src: Bitmap): Bitmap {
        val ratio = 2.4f
        var cw = src.width
        var ch = (cw / ratio).toInt()
        if (ch > src.height) {
            ch = src.height
            cw = (ch * ratio).toInt()
        }
        val crop = Bitmap.createBitmap(
            src, (src.width - cw) / 2, (src.height - ch) / 2, cw.coerceAtLeast(1), ch.coerceAtLeast(1)
        )
        // Двойное сжатие + растяжение с фильтрацией = мягкое размытие без RenderScript
        val tiny = Bitmap.createScaledBitmap(crop, 24, 10, true)
        val tinier = Bitmap.createScaledBitmap(tiny, 12, 5, true)
        val mid = Bitmap.createScaledBitmap(tinier, 48, 20, true)
        val out = Bitmap.createScaledBitmap(mid, 480, 200, true).copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawColor(0x8C000000.toInt())
        return out
    }
}
