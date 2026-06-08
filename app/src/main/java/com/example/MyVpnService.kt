package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.AppBlockDatabase
import com.example.data.AppBlockRepository
import com.example.data.AppBlockRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileInputStream
import java.nio.ByteBuffer

class MyVpnService : VpnService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnPacketThread: Thread? = null
    private var connectivityManager: ConnectivityManager? = null

    private lateinit var repository: AppBlockRepository

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d(TAG, "Network became available, updating firewall configuration")
            rebuildVpn()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            Log.d(TAG, "Network capabilities changed, updating firewall configuration")
            rebuildVpn()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Firewall service onCreate called")
        val database = AppBlockDatabase.getInstance(this)
        repository = AppBlockRepository(database.appBlockRuleDao)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Register a callback to listen to dynamic connections (Mobile Data / WiFi switches)
        try {
            val builder = NetworkRequest.Builder()
            connectivityManager?.registerNetworkCallback(builder.build(), networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Could not register connectivity manager network callback", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand with Action: $action")

        when (action) {
            ACTION_START -> {
                startFirewall()
            }
            ACTION_STOP -> {
                stopFirewall()
                stopSelf()
            }
            ACTION_REBUILD -> {
                rebuildVpn()
            }
        }
        return START_STICKY
    }

    private fun startFirewall() {
        createNotificationChannel()
        val notification = createNotification("Initializing network interface...")
        
        // Start as foreground service to comply with Android regulations
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning.value = true
        rebuildVpn()
    }

    private fun stopFirewall() {
        Log.d(TAG, "Stopping No-Root Firewall")
        isRunning.value = false
        blockedAppCount.value = 0
        
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}

        closeVpnInterface()
        stopForeground(true)
        serviceJob.cancelChildren()
    }

    @Synchronized
    private fun rebuildVpn() {
        serviceScope.launch {
            try {
                val rules = repository.getAllRules()
                val isMobile = isMobileNetworkActive()
                
                // Filter which applications must be blocked under the current active network
                val blockedApps = rules.filter { rule ->
                    if (isMobile) {
                        rule.blockedMobileData
                    } else {
                        rule.blockedWifi
                    }
                }

                Log.d(TAG, "Rebuilding VPN interface. Active Network: ${if (isMobile) "MOBILE DATA" else "WIFI"}. Blocked Apps count: ${blockedApps.size}")
                
                // Update stats
                blockedAppCount.value = blockedApps.size

                val notificationText = if (blockedApps.isEmpty()) {
                    "Firewall active (monitoring, 0 apps suspended)"
                } else {
                    "Firewall active (blocking ${blockedApps.size} apps)"
                }
                updateNotification(notificationText)

                // If no apps to block, close existing interface but keep helper active
                if (blockedApps.isEmpty()) {
                    closeVpnInterface()
                    return@launch
                }

                // Establish the VPN
                val builder = Builder()
                    .setSession("No-Root Firewall")
                    .addAddress("10.0.0.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fd00::1", 128)
                    .addRoute("::", 0)

                // Set callback intent to reopen main screen on clicking notification
                val mainActivityIntent = Intent(this@MyVpnService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getActivity(
                    this@MyVpnService, 0, mainActivityIntent, pendingIntentFlags
                )
                builder.setConfigureIntent(pendingIntent)

                // Add the blocked applications to VPN tunnel
                var addedCount = 0
                for (app in blockedApps) {
                    try {
                        builder.addAllowedApplication(app.packageName)
                        addedCount++
                        Log.d(TAG, "Routing and dropping traffic for package: ${app.packageName}")
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "App not installed anymore: ${app.packageName}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not add allowed application: ${app.packageName}", e)
                    }
                }

                // If none could actually be added, close tunnel interface
                if (addedCount == 0) {
                    closeVpnInterface()
                    return@launch
                }

                // Close older interface first before recreating
                val oldInterface = vpnInterface
                closeVpnInterface()

                val newInterface = builder.establish()
                if (newInterface != null) {
                    vpnInterface = newInterface
                    startPacketDropperLoop(newInterface)
                } else {
                    Log.e(TAG, "Failed to establish VPN interface builder returned null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error while rebuilding VPN configuration", e)
            }
        }
    }

    private fun startPacketDropperLoop(pfd: ParcelFileDescriptor) {
        vpnPacketThread = Thread({
            val buffer = ByteBuffer.allocate(32768)
            val inputStream = FileInputStream(pfd.fileDescriptor)
            try {
                while (!Thread.interrupted()) {
                    val bytesRead = inputStream.read(buffer.array())
                    if (bytesRead > 0) {
                        // Drop the packet. We intentionally omit forwarding it,
                        // effectively blocking internet traffic for all matched allowed apps!
                        buffer.clear()
                    } else {
                        Thread.sleep(100)
                    }
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "Vpn packet dropper thread interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "Vpn packet dropper loop error", e)
            } finally {
                try {
                    inputStream.close()
                } catch (_: Exception) {}
            }
        }, "FirewallPacketDropperThread")
        vpnPacketThread?.start()
    }

    private fun closeVpnInterface() {
        vpnPacketThread?.interrupt()
        vpnPacketThread = null
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN connection descriptor", e)
        }
        vpnInterface = null
    }

    private fun isMobileNetworkActive(): Boolean {
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Firewall Network Tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of secure system firewall rules block state."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, pendingIntentFlags
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock) // Native secure lock icon symbol fallback
            .setContentTitle("No-Root Firewall Running")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        return builder.build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.notify(NOTIFICATION_ID, createNotification(text))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Firewall service onDestroy")
        stopFirewall()
        serviceJob.cancel()
    }

    companion object {
        private const val TAG = "NoRootFirewallService"
        private const val CHANNEL_ID = "NoRootFirewallChannel"
        private const val NOTIFICATION_ID = 11099

        // Flow properties of status to bind cleanly with UI ViewModels
        val isRunning = MutableStateFlow(false)
        val blockedAppCount = MutableStateFlow(0)

        const val ACTION_START = "com.example.ACTION_START"
        const val ACTION_STOP = "com.example.ACTION_STOP"
        const val ACTION_REBUILD = "com.example.ACTION_REBUILD"
    }
}
