package io.github.mzalmuzakki.gesitsmid

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads Google Maps' turn-by-turn navigation notification and hands the next instruction to the
 * MID service. Every other app's notifications are ignored immediately and nothing is stored or sent
 * anywhere except to the bike's dashboard. Works only after you allow "notification access".
 */
class MapsNotifListener : NotificationListenerService() {
    companion object { const val MAPS = "com.google.android.apps.maps" }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS) return
        if (!Settings(this).navOn) return
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val ins = NavParser.parse(title, text, sub) ?: return
        // Only the navigation card: Maps marks it "navigation"; otherwise require a distance like "200 m".
        if (n.category != Notification.CATEGORY_NAVIGATION && ins.distance == null) return
        Bike.nav = ins; Bike.navAt = System.currentTimeMillis()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS && sbn.notification?.category == Notification.CATEGORY_NAVIGATION) Bike.nav = null
    }
}
