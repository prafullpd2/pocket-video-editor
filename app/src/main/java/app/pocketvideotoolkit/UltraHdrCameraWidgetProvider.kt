package app.pocketvideotoolkit

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class UltraHdrCameraWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val launch = Intent(context, MainActivity::class.java).apply {
            putExtra("open_ultra_hdr_camera", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(context, 5201, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.ultra_hdr_camera_widget)
            views.setOnClickPendingIntent(R.id.camera_widget_root, pending)
            manager.updateAppWidget(id, views)
        }
    }
}
