package com.mavinokta.mncast.mirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class AndroidMirrorService : Service() {
    companion object {
        const val CONTROL_PORT = 53591
        const val VIDEO_PORT = 53592
        private const val CHANNEL = "mncast_receiver"
        private const val NOTIFICATION_ID = 5359
    }

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("MN Cast")
            .setContentText("AirPlay ve Android yansıtma için hazır")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        startServer()
        registerNsd()
    }

    private fun startServer() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread({
            try {
                server = ServerSocket(CONTROL_PORT).apply { reuseAddress = true }
                while (running.get()) {
                    val socket = server?.accept() ?: break
                    handleClient(socket)
                }
            } catch (_: Throwable) {
            } finally {
                running.set(false)
            }
        }, "MN-Cast-Control").also { it.start() }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            MirrorEngine.startClient(socket, VIDEO_PORT) { width, height ->
                val i = Intent(this, AndroidMirrorActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("width", width)
                    .putExtra("height", height)
                startActivity(i)
            }
        } catch (_: Throwable) {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun registerNsd() {
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = "MN Cast"
            serviceType = "_mncast._tcp."
            port = CONTROL_PORT
            setAttribute("v", "1")
            setAttribute("video", VIDEO_PORT.toString())
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }.also { nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, it) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "MN Cast receiver", NotificationManager.IMPORTANCE_MIN))
        }
    }

    override fun onDestroy() {
        running.set(false)
        try { server?.close() } catch (_: Throwable) {}
        try { registration?.let { nsdManager?.unregisterService(it) } } catch (_: Throwable) {}
        MirrorEngine.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
