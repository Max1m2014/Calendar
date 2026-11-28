package dev.mediawidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Экран настройки: выдать доступ и добавить виджет. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var grant: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        fun text(t: String, size: Float, top: Int = 0) = TextView(this).apply {
            text = t
            textSize = size
            setPadding(0, dp(top), 0, 0)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        root.addView(text(getString(R.string.app_name), 28f))
        status = text("", 16f, 16)
        root.addView(status)

        grant = button("1. Разрешить доступ к медиа") {
            val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    MediaHub.listenerComponent(this).flattenToString()
                )
            runCatching { startActivity(i) }.onFailure {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        root.addView(grant)

        root.addView(text(
            "Если переключатель серый («Ограниченный параметр»): " +
                "Сведения о приложении → ⋮ справа сверху → «Разрешить ограниченные настройки», " +
                "затем снова нажмите кнопку выше.", 13f, 8
        ))
        root.addView(button("Открыть сведения о приложении") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        })

        root.addView(button("2. Добавить виджет на экран") {
            val mgr = getSystemService(AppWidgetManager::class.java)
            if (mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(ComponentName(this, MediaWidgetProvider::class.java), null, null)
            } else {
                Toast.makeText(this, "Добавьте вручную: долгое нажатие на экран → Виджеты", Toast.LENGTH_LONG).show()
            }
        })

        root.addView(text("Фон виджета", 20f, 28))
        val labels = mapOf(
            BgMode.ADAPTIVE to "Адаптивный — цвет и фон из обложки",
            BgMode.DARK to "Тёмный",
            BgMode.SYSTEM to "Светлый / тёмный — как тема телефона",
            BgMode.AMOLED to "AMOLED — чисто чёрный"
        )
        val current = WidgetPrefs.bgMode(this)
        val group = RadioGroup(this)
        for (mode in BgMode.entries) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = labels[mode]
                tag = mode
                textSize = 16f
                setPadding(dp(8), dp(10), 0, dp(10))
            }
            group.addView(rb)
            if (mode == current) group.check(rb.id)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val mode = g.findViewById<RadioButton>(checkedId)?.tag as? BgMode ?: return@setOnCheckedChangeListener
            WidgetPrefs.setBgMode(this, mode)
            WidgetRenderer.updateAll(this)
        }
        root.addView(group)
        root.addView(text("В виде 2×2 фоном всегда служит сама обложка.", 13f, 4))

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        val ok = MediaHub.hasAccess(this)
        status.text = if (ok) "✅ Доступ есть. Виджет показывает то, что сейчас играет."
        else "Чтобы виджет видел музыку и видео, нужен доступ к уведомлениям (медиасессиям)."
        grant.visibility = if (ok) View.GONE else View.VISIBLE
        MediaHub.requestRebind(this)
        WidgetRenderer.updateAll(this)
    }
}
