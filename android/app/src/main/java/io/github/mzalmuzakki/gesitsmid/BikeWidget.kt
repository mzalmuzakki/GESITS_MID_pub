package io.github.mzalmuzakki.gesitsmid

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Locale

/** Home-screen widget: battery %, range and connection. Updated by the service (no timers of its own). */
class BikeWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = update(ctx)

    companion object {
        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BikeWidget::class.java))
            if (ids.isEmpty()) return
            val t = Bike.telemetry
            val v = RemoteViews(ctx.packageName, R.layout.widget_bike)
            v.setTextViewText(R.id.w_soc, (t.raw["SOC"] ?: "--") + "%")
            v.setTextViewText(R.id.w_range, (Bike.rangeKm()?.let { String.format(Locale.US, "%.0f", it) } ?: "--") + " km")
            v.setTextViewText(R.id.w_status, if (Bike.connected) "● " + L.t("Connected") else "○ " + L.t("Offline"))
            val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_root, open)
            mgr.updateAppWidget(ids, v)
        }
    }
}
