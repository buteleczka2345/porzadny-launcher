package pl.porzadny.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Pusta usługa na pierwszym planie: nic nie robi (zero pracy procesora), ale dzięki niej system nie wyrzuca
 * launchera z RAM-u, więc ekran główny pokazuje się od razu, bez ponownego wczytywania ikon.
 * Bez zgody na powiadomienia (Android 13+) powiadomienie jest niewidoczne, a usługa i tak działa.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ram", "Launcher w pamięci", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "ram")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Porządny Launcher gotowy")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)
        } catch (e: Exception) {
            // system nie pozwolił (np. restart w tle) — launcher działa dalej, tylko bez ochrony przed wyrzuceniem z RAM
            stopSelf()
        }
        return START_STICKY
    }
}
