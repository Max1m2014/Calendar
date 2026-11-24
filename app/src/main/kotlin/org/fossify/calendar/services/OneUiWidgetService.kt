package org.fossify.calendar.services

import android.content.Intent
import android.widget.RemoteViewsService
import org.fossify.calendar.adapters.OneUiWidgetAdapter

class OneUiWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent) = OneUiWidgetAdapter(applicationContext, intent)
}
