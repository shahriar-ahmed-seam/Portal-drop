package app.portaldrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

internal class OfflineHotspotService : Service() {
    sealed interface State {
        data object Off : State
        data object Starting : State
        class Active(val ssid: String, val passphrase: String?) : State
        data object SystemHotspot : State
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
        val action = intent?.action
        if (action != ACTION_START && action != ACTION_SYSTEM) {
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
            if (action == ACTION_SYSTEM) {
                update(this, State.SystemHotspot)
                notifications.notify(NOTIFICATION_ID, notification("Join your phone’s hotspot from your computer.", system = true))
                return START_NOT_STICKY
            }
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            if (wifi == null) {
                fail("This device has no Wi-Fi adapter available.")
            } else {
                if (Build.VERSION.SDK_INT >= 36) {
                    val config = SoftApConfiguration.Builder()
                    val passphrase = intent?.getStringExtra(EXTRA_PASSPHRASE)
                    if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                        config.setPassphrase(
                            passphrase,
                            if (passphrase == null) SoftApConfiguration.SECURITY_TYPE_OPEN
                            else SoftApConfiguration.SECURITY_TYPE_WPA2_PSK,
                        )
                    }
                    wifi.startLocalOnlyHotspotWithConfiguration(config.build(), mainExecutor, callback)
                } else {
                    wifi.startLocalOnlyHotspot(callback, main)
                }
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
                fail("Android did not provide the hotspot’s Wi-Fi details. Try starting it again.")
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
                ERROR_INCOMPATIBLE_MODE -> "Disconnect from your current Wi-Fi network or use the hotspot in Android Settings."
                ERROR_NO_CHANNEL -> "Android could not find a Wi-Fi channel. Try again in a different location."
                ERROR_TETHERING_DISALLOWED -> "Android or your device administrator has disabled hotspots."
                else -> "Android could not start the hotspot. Check Wi-Fi and try again."
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun credentials(reservation: WifiManager.LocalOnlyHotspotReservation): State.Active? {
        val (ssid, passphrase) = if (Build.VERSION.SDK_INT >= 30) {
            val config = reservation.softApConfiguration
            val password = config.passphrase
            if (password.isNullOrBlank() && config.securityType != SoftApConfiguration.SECURITY_TYPE_OPEN) return null
            config.ssid to password
        } else {
            val wifi = reservation.wifiConfiguration ?: return null
            val password = wifi.preSharedKey.cleanWifiValue()?.takeIf { it.isNotBlank() } ?: return null
            wifi.SSID.cleanWifiValue() to password
        }
        if (ssid.isNullOrBlank() || ssid == WifiManager.UNKNOWN_SSID) return null
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

    private fun notification(message: String, system: Boolean = false): Notification {
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
            .addAction(Notification.Action.Builder(null, if (system) "End setup" else "Stop hotspot", stop).build())
        if (Build.VERSION.SDK_INT >= 31) {
            notice.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return notice.build()
    }

    companion object {
        const val ACTION_UPDATE = "app.portaldrop.HOTSPOT_UPDATE"
        const val ACTION_OPEN = "app.portaldrop.OPEN_HOTSPOT"
        private const val ACTION_START = "app.portaldrop.START_HOTSPOT"
        private const val ACTION_SYSTEM = "app.portaldrop.USE_SYSTEM_HOTSPOT"
        private const val ACTION_STOP = "app.portaldrop.STOP_HOTSPOT"
        private const val EXTRA_PASSPHRASE = "password"
        private const val CHANNEL = "offline_hotspot"
        private const val NOTIFICATION_ID = 220
        private const val START_TIMEOUT_MS = 30_000L

        @Volatile
        var state: State = State.Off
            private set

        fun isActive(): Boolean = state is State.Active || state is State.SystemHotspot

        fun supportsCustomPassphrase(): Boolean =
            Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1

        fun start(context: Context, passphrase: String? = null) {
            if (state is State.Starting || isActive()) return
            if (passphrase != null && !supportsCustomPassphrase()) {
                update(context, State.Failed("Use Android Settings to set a hotspot password on this phone."))
                return
            }
            if (passphrase != null && !passphrase.isValidHotspotPassphrase()) {
                update(context, State.Failed("Use 8–63 printable ASCII characters for the hotspot password."))
                return
            }
            launch(context, ACTION_START, passphrase)
        }

        fun useSystemHotspot(context: Context) = launch(context, ACTION_SYSTEM, null)

        private fun launch(context: Context, action: String, passphrase: String?) {
            if (state is State.Starting || isActive()) return
            val store = PortalStore.get(context)
            if (store.peer() == null) {
                update(context, State.Failed("Pair your computer before starting hotspot sharing."))
                return
            }
            update(context, State.Starting)
            try {
                context.startForegroundService(
                    Intent(context, OfflineHotspotService::class.java)
                        .setAction(action)
                        .putExtra(EXTRA_PASSPHRASE, passphrase),
                )
                if (store.backgroundEnabled()) runCatching { TransferService.start(context) }
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

private fun String.isValidHotspotPassphrase(): Boolean =
    length in 8..63 && all { it in ' '..'~' }
