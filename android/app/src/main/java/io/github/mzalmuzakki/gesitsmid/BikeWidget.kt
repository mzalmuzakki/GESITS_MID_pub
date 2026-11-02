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
            val frozen = Bike.dataFrozen              // values that don't change are not shown as live
            v.setTextViewText(R.id.w_soc, ((if (frozen) null else t.raw["SOC"]) ?: "--") + "%")
            v.setTextViewText(R.id.w_range, ((if (frozen) null else Bike.rangeKm())?.let { String.format(Locale.US, "%.0f", it) } ?: "--") + " km")
            v.setTextViewText(R.id.w_status, when {
                Bike.midHungSince > 0L -> "⚠ " + L.t("MID not answering")
                frozen -> "⚠ " + L.t("MID values look frozen")
                Bike.connected -> "● " + L.t("Connected")
                else -> "○ " + L.t("Offline")
            })
            val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_root, open)
            mgr.updateAppWidget(ids, v)
        }
    }
}
