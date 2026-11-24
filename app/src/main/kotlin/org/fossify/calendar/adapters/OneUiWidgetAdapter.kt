package org.fossify.calendar.adapters

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Build
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.annotation.ColorRes
import org.fossify.calendar.R
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.eventsHelper
import org.fossify.calendar.extensions.seconds
import org.fossify.calendar.helpers.DAILY_VIEW
import org.fossify.calendar.helpers.DAY_CODE
import org.fossify.calendar.helpers.EVENT_ID
import org.fossify.calendar.helpers.EVENT_OCCURRENCE_TS
import org.fossify.calendar.helpers.Formatter
import org.fossify.calendar.helpers.IS_TASK
import org.fossify.calendar.helpers.VIEW_TO_OPEN
import org.fossify.calendar.helpers.getNowSeconds
import org.fossify.calendar.models.Event
import org.joda.time.DateTime

/** Upcoming events for the One UI widget, grouped by day ("Today", "Tomorrow", "Wed, 26 Nov"). */
class OneUiWidgetAdapter(private val context: Context, private val intent: Intent) : RemoteViewsService.RemoteViewsFactory {

    private sealed class Row {
        data class Section(val title: String, val code: String, val isToday: Boolean) : Row()
        data class Item(val event: Event, val dimmed: Boolean) : Row()
    }

    private var rows: List<Row> = emptyList()

    override fun onCreate() {}

    override fun onDestroy() {}

    override fun getCount() = rows.size

    override fun getViewTypeCount() = 2

    override fun getItemId(position: Int) = position.toLong()

    override fun hasStableIds() = true

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        return when (val row = rows.getOrNull(position)) {
            is Row.Section -> sectionView(row)
            is Row.Item -> itemView(row)
            null -> RemoteViews(context.packageName, R.layout.widget_oneui_section)
        }
    }

    private fun sectionView(row: Row.Section): RemoteViews {
        return RemoteViews(context.packageName, R.layout.widget_oneui_section).apply {
            setTextViewText(R.id.oneui_section_title, row.title)
            setColor(this, R.id.oneui_section_title, if (row.isToday) R.color.oneui_accent else R.color.oneui_text_secondary)
            setOnClickFillInIntent(R.id.oneui_section_title, Intent().apply {
                putExtra(DAY_CODE, row.code)
                putExtra(VIEW_TO_OPEN, DAILY_VIEW)
            })
        }
    }

    private fun itemView(row: Row.Item): RemoteViews {
        val event = row.event
        return RemoteViews(context.packageName, R.layout.widget_oneui_item).apply {
            setInt(R.id.oneui_item_bar, "setColorFilter", event.color)
            setInt(R.id.oneui_item_bar, "setImageAlpha", if (row.dimmed) 110 else 255)
            setTextViewText(R.id.oneui_item_title, event.title)
            setTextViewText(R.id.oneui_item_time, timeText(event))
            setColor(this, R.id.oneui_item_title, if (row.dimmed) R.color.oneui_text_secondary else R.color.oneui_text)

            val strike = event.isTask() && event.isTaskCompleted()
            val flags = if (strike) Paint.ANTI_ALIAS_FLAG or Paint.STRIKE_THRU_TEXT_FLAG else Paint.ANTI_ALIAS_FLAG
            setInt(R.id.oneui_item_title, "setPaintFlags", flags)

            setOnClickFillInIntent(R.id.oneui_item_holder, Intent().apply {
                putExtra(EVENT_ID, event.id ?: 0L)
                putExtra(EVENT_OCCURRENCE_TS, event.startTS)
                putExtra(IS_TASK, event.isTask())
            })
        }
    }

    private fun timeText(event: Event): String {
        val startCode = Formatter.getDayCodeFromTS(event.startTS)
        val endCode = Formatter.getDayCodeFromTS(event.endTS)
        var text = if (event.getIsAllDay()) {
            context.getString(R.string.all_day)
        } else if (event.startTS == event.endTS) {
            Formatter.getTimeFromTS(context, event.startTS)
        } else {
            "${Formatter.getTimeFromTS(context, event.startTS)} – ${Formatter.getTimeFromTS(context, event.endTS)}"
        }
        if (startCode != endCode) {
            text += " → " + shortDate(endCode)
        }
        if (event.location.isNotBlank()) {
            text += " · " + event.location.trim()
        }
        return text
    }

    private fun shortDate(code: String): String = Formatter.getDateTimeFromCode(code).toString("d MMM")

    override fun onDataSetChanged() {
        val todayStart = DateTime().withTimeAtStartOfDay()
        val fromTS = todayStart.seconds()
        val toTS = todayStart.plusDays(DAYS_AHEAD).seconds()
        val todayCode = Formatter.getDayCodeFromDateTime(todayStart)
        val tomorrowCode = Formatter.getDayCodeFromDateTime(todayStart.plusDays(1))
        val now = getNowSeconds()
        val dimPast = context.config.dimPastEvents

        var result: List<Row> = emptyList()
        context.eventsHelper.getEventsSync(fromTS, toTS, applyTypeFilter = true) { events ->
            val sorted = events
                .filter { it.endTS >= fromTS || it.startTS >= fromTS }
                .sortedWith(compareBy<Event>({ groupCode(it, todayCode) }, { !it.getIsAllDay() }, { it.startTS }, { it.title }))

            val list = ArrayList<Row>()
            var prevCode = ""
            for (event in sorted) {
                val code = groupCode(event, todayCode)
                if (code != prevCode) {
                    val title = when (code) {
                        todayCode -> context.getString(R.string.oneui_today) + " · " + Formatter.getDateTimeFromCode(code).toString("EEE, d MMM")
                        tomorrowCode -> context.getString(R.string.oneui_tomorrow)
                        else -> Formatter.getDateTimeFromCode(code).toString("EEE, d MMM")
                    }
                    list.add(Row.Section(title, code, code == todayCode))
                    prevCode = code
                }
                val isPast = !event.getIsAllDay() && event.endTS < now
                val dimmed = (dimPast && isPast && !event.isTask()) || (event.isTask() && event.isTaskCompleted())
                list.add(Row.Item(event, dimmed))
            }
            result = list
        }
        rows = result
    }

    /** Events that started before today but are still running are shown under "Today". */
    private fun groupCode(event: Event, todayCode: String): String {
        val code = Formatter.getDayCodeFromTS(event.startTS)
        return if (code < todayCode) todayCode else code
    }

    private fun setColor(views: RemoteViews, viewId: Int, @ColorRes color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(viewId, "setTextColor", color)
        } else {
            views.setTextColor(viewId, context.getColor(color))
        }
    }

    companion object {
        private const val DAYS_AHEAD = 31
    }
}
