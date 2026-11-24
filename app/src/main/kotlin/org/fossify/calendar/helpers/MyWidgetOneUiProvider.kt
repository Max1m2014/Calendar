package org.fossify.calendar.helpers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import org.fossify.calendar.R
import org.fossify.calendar.activities.SplashActivity
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.launchNewEventOrTaskActivity
import org.fossify.calendar.interfaces.MonthlyCalendar
import org.fossify.calendar.models.DayMonthly
import org.fossify.calendar.services.OneUiWidgetService
import org.fossify.commons.extensions.getLaunchIntent
import org.joda.time.DateTime
import org.joda.time.DateTimeConstants

/**
 * "Month + events" widget styled after the Samsung One UI calendar widget:
 * big month title, red Sundays / blue Saturdays, filled circle for today,
 * coloured event dots under the days and an upcoming-events list below.
 * Colours come from resources (values / values-night), so it follows the system dark mode.
 */
class MyWidgetOneUiProvider : AppWidgetProvider() {

    companion object {
        private const val PREV = "oneui_prev"
        private const val NEXT = "oneui_next"
        private const val GO_TO_TODAY = "oneui_go_to_today"
        private const val NEW_EVENT = "oneui_new_event"
        private const val DAYS_IN_WEEK = 7
        private const val MAX_DOTS = 3

        private var targetDate: DateTime = DateTime.now().withDayOfMonth(1)

        fun getComponentName(context: Context) = ComponentName(context, MyWidgetOneUiProvider::class.java)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        performUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PREV -> {
                targetDate = targetDate.minusMonths(1)
                performUpdate(context)
            }

            NEXT -> {
                targetDate = targetDate.plusMonths(1)
                performUpdate(context)
            }

            GO_TO_TODAY -> {
                targetDate = DateTime.now().withDayOfMonth(1)
                performUpdate(context)
            }

            NEW_EVENT -> context.launchNewEventOrTaskActivity()
            else -> super.onReceive(context, intent)
        }
    }

    private fun performUpdate(context: Context) {
        MonthlyCalendarImpl(monthlyCalendar, context).getMonth(targetDate)
    }

    private val monthlyCalendar = object : MonthlyCalendar {
        override fun updateMonthlyCalendar(
            context: Context,
            month: String,
            days: ArrayList<DayMonthly>,
            checkedEvents: Boolean,
            currTargetDate: DateTime
        ) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            appWidgetManager.getAppWidgetIds(getComponentName(context)).forEach { widgetId ->
                val views = RemoteViews(context.packageName, R.layout.widget_oneui)

                views.setTextViewText(R.id.oneui_month, Formatter.getMonthName(context, currTargetDate.monthOfYear))
                views.setTextViewText(R.id.oneui_year, currTargetDate.year.toString())

                val isCurrentMonth = currTargetDate.withTime(0, 0, 0, 0) ==
                    DateTime.now().withDayOfMonth(1).withTime(0, 0, 0, 0)
                views.setViewVisibility(R.id.oneui_today, if (isCurrentMonth) View.GONE else View.VISIBLE)

                setupBroadcast(context, views, PREV, R.id.oneui_prev)
                setupBroadcast(context, views, NEXT, R.id.oneui_next)
                setupBroadcast(context, views, GO_TO_TODAY, R.id.oneui_today)
                setupBroadcast(context, views, NEW_EVENT, R.id.oneui_add)

                val monthCode = days.firstOrNull { it.code.substring(6) == "01" }?.code ?: Formatter.getTodayCode()
                setupOpenApp(context, views, R.id.oneui_title, monthCode, MONTHLY_VIEW, monthCode.substring(0, 6).toInt())

                updateDayLabels(context, views)
                updateDays(context, views, days)
                setupList(context, views, widgetId)

                try {
                    appWidgetManager.updateAppWidget(widgetId, views)
                    appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.oneui_list)
                } catch (ignored: RuntimeException) {
                }
            }
        }
    }

    /** Joda day of week (1 = Monday … 7 = Sunday) of a grid column. */
    private fun columnDayOfWeek(context: Context, column: Int): Int {
        return (column + context.config.firstDayOfWeek - 1) % DAYS_IN_WEEK + 1
    }

    private fun updateDayLabels(context: Context, views: RemoteViews) {
        val letters = context.resources.getStringArray(org.fossify.commons.R.array.week_days_short)
        for (i in 0 until DAYS_IN_WEEK) {
            val id = context.resources.getIdentifier("oneui_label_$i", "id", context.packageName)
            val dayOfWeek = columnDayOfWeek(context, i)
            views.setTextViewText(id, letters[dayOfWeek - 1])
            val color = when (dayOfWeek) {
                DateTimeConstants.SUNDAY -> R.color.oneui_sunday
                DateTimeConstants.SATURDAY -> R.color.oneui_saturday
                else -> R.color.oneui_text_secondary
            }
            setTextColorRes(context, views, id, color)
        }
    }

    private fun updateDays(context: Context, views: RemoteViews, days: List<DayMonthly>) {
        val packageName = context.packageName
        for (i in days.indices) {
            val day = days[i]
            val containerId = context.resources.getIdentifier("oneui_day_$i", "id", packageName)
            views.removeAllViews(containerId)

            val cell = RemoteViews(packageName, R.layout.widget_oneui_day)
            val number = SpannableString(day.value.toString())
            if (day.isToday) number.setSpan(StyleSpan(Typeface.BOLD), 0, number.length, 0)
            cell.setTextViewText(R.id.oneui_day_number, number)

            val dayOfWeek = columnDayOfWeek(context, i % DAYS_IN_WEEK)
            val color = when {
                day.isToday -> R.color.oneui_on_accent
                dayOfWeek == DateTimeConstants.SUNDAY -> if (day.isThisMonth) R.color.oneui_sunday else R.color.oneui_sunday_dim
                dayOfWeek == DateTimeConstants.SATURDAY -> if (day.isThisMonth) R.color.oneui_saturday else R.color.oneui_saturday_dim
                else -> if (day.isThisMonth) R.color.oneui_text else R.color.oneui_text_dim
            }
            setTextColorRes(context, cell, R.id.oneui_day_number, color)
            cell.setViewVisibility(R.id.oneui_day_circle, if (day.isToday) View.VISIBLE else View.GONE)

            val dotColors = day.dayEvents
                .distinctBy { it.id to it.startTS }
                .sortedWith(compareBy({ !it.getIsAllDay() }, { it.startTS }))
                .map { it.color }
                .take(MAX_DOTS)
            val dotIds = intArrayOf(R.id.oneui_dot_0, R.id.oneui_dot_1, R.id.oneui_dot_2)
            for (d in dotIds.indices) {
                val dotColor = dotColors.getOrNull(d)
                if (dotColor == null) {
                    cell.setViewVisibility(dotIds[d], View.GONE)
                } else {
                    cell.setViewVisibility(dotIds[d], View.VISIBLE)
                    cell.setInt(dotIds[d], "setColorFilter", dotColor)
                    if (!day.isThisMonth) cell.setInt(dotIds[d], "setImageAlpha", 110)
                }
            }

            views.addView(containerId, cell)
            setupOpenApp(context, views, containerId, day.code, DAILY_VIEW, day.code.toInt())
        }
    }

    private fun setupList(context: Context, views: RemoteViews, widgetId: Int) {
        Intent(context, OneUiWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            views.setRemoteAdapter(R.id.oneui_list, this)
        }
        val startActivityIntent = context.getLaunchIntent() ?: Intent(context, SplashActivity::class.java)
        val template = PendingIntent.getActivity(
            context, widgetId, startActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(R.id.oneui_list, template)
        views.setEmptyView(R.id.oneui_list, R.id.oneui_empty)
    }

    private fun setupBroadcast(context: Context, views: RemoteViews, action: String, id: Int) {
        val intent = Intent(context, MyWidgetOneUiProvider::class.java).setAction(action)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(id, pendingIntent)
    }

    private fun setupOpenApp(context: Context, views: RemoteViews, id: Int, dayCode: String, view: Int, requestCode: Int) {
        val intent = (context.getLaunchIntent() ?: Intent(context, SplashActivity::class.java)).apply {
            putExtra(DAY_CODE, dayCode)
            putExtra(VIEW_TO_OPEN, view)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(id, pendingIntent)
    }

    /**
     * On Android 12+ the colour resource is resolved by the launcher, so the widget
     * switches between light and dark instantly together with the system theme.
     */
    private fun setTextColorRes(context: Context, views: RemoteViews, viewId: Int, @ColorRes color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorStateList(viewId, "setTextColor", color)
        } else {
            views.setTextColor(viewId, context.getColor(color))
        }
    }
}
