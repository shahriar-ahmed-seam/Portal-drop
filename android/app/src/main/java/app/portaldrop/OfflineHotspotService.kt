package app.portaldrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

internal class OfflineHotspotService : Service() {
    sealed interface State {
        data object Off : State
        data object Starting : State
        class Active(val ssid: String, val passphrase: String) : State
        data class Failed(val message: String) : State
    }

    private val main = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var requested = false
    private var stopping = false
    private var failure: String? = null
    private val timeout = Runnable {
        fail("The hotspot took too long to start. Wait a moment, then try again.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (requested || stopping) return START_NOT_STICKY
        requested = true
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Offline hotspot", NotificationManager.IMPORTANCE_LOW),
        )
        try {
            startForeground(
                NOTIFICATION_ID, notification("Starting your offline hotspot…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            if (wifi == null) {
                fail("This device has no Wi-Fi adapter available.")
            } else {
                wifi.startLocalOnlyHotspot(callback, main)
                main.postDelayed(timeout, START_TIMEOUT_MS)
            }
        } catch (_: SecurityException) {
            fail("Allow Wi-Fi hotspot access for Portal Drop in Android Settings and try again.")
        } catch (_: RuntimeException) {
            fail("Android could not start the hotspot. Check Wi-Fi and try again.")
        }
        return START_NOT_STICKY
    }

    private val callback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(available: WifiManager.LocalOnlyHotspotReservation) {
            main.removeCallbacks(timeout)
            if (stopping) {
                runCatching { available.close() }
                return
            }
            reservation = available
            val credentials = runCatching { credentials(available) }.getOrNull()
            if (credentials == null) {
                fail("Android did not provide a protected Wi-Fi network. Try starting the hotspot again.")
                return
            }
            update(this@OfflineHotspotService, credentials)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("Connect your computer to the hotspot."))
        }

        override fun onStopped() {
            if (!stopping) fail("Android stopped the hotspot. Start it again to reconnect.")
        }

        override fun onFailed(reason: Int) {
            if (stopping) return
            fail(when (reason) {
                ERROR_INCOMPATIBLE_MODE -> "Turn off the existing hotspot or Wi-Fi Direct session in Android Settings, then try again."
                ERROR_NO_CHANNEL -> "Android could not find a Wi-Fi channel. Try again in a different location."
                ERROR_TETHERING_DISALLOWED -> "Android or your device administrator has disabled hotspots."
                else -> "Android could not start the hotspot. Check Wi-Fi and try again."
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun credentials(reservation: WifiManager.LocalOnlyHotspotReservation): State.Active? {
        val (ssid, passphrase) = if (Build.VERSION.SDK_INT >= 30) {
            reservation.softApConfiguration.let { it.ssid to it.passphrase }
        } else {
            val wifi = reservation.wifiConfiguration ?: return null
            wifi.SSID.cleanWifiValue() to wifi.preSharedKey.cleanWifiValue()
        }
        if (ssid.isNullOrBlank() || passphrase.isNullOrBlank()) return null
        return State.Active(ssid, passphrase)
    }

    private fun String?.cleanWifiValue(): String? = this?.removeSurrounding("\"")

    private fun fail(message: String) {
        stopping = true
        failure = message
        main.removeCallbacks(timeout)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        main.removeCallbacks(timeout)
        runCatching { reservation?.close() }
        reservation = null
        update(this, failure?.let { State.Failed(it) } ?: State.Off)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OfflineHotspotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setAction(ACTION_OPEN),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notice = Notification.Builder(this, CHANNEL)
            .setContentTitle("Portal Drop offline hotspot")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_portal_notification)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop hotspot", stop).build())
        if (Build.VERSION.SDK_INT >= 31) {
            notice.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return notice.build()
    }

    companion object {
        const val ACTION_UPDATE = "app.portaldrop.HOTSPOT_UPDATE"
        const val ACTION_OPEN = "app.portaldrop.OPEN_HOTSPOT"
        private const val ACTION_START = "app.portaldrop.START_HOTSPOT"
        private const val ACTION_STOP = "app.portaldrop.STOP_HOTSPOT"
        private const val CHANNEL = "offline_hotspot"
        private const val NOTIFICATION_ID = 220
        private const val START_TIMEOUT_MS = 30_000L

        @Volatile
        var state: State = State.Off
            private set

        fun isActive(): Boolean = state is State.Active

        fun start(context: Context) {
            if (state is State.Starting || isActive()) return
            update(context, State.Starting)
            try {
                context.startForegroundService(Intent(context, OfflineHotspotService::class.java).setAction(ACTION_START))
            } catch (_: RuntimeException) {
                update(context, State.Failed("Open Portal Drop and try starting the hotspot again."))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OfflineHotspotService::class.java))
        }

        private fun update(context: Context, next: State) {
            val wasActive = isActive()
            state = next
            if (wasActive != isActive()) TransferService.hotspotChanged()
            context.sendBroadcast(Intent(ACTION_UPDATE).setPackage(context.packageName))
        }
    }
}
