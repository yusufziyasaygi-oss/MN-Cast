package com.mavinokta.mncast.mirror

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class AndroidMirrorActivity : Activity(), SurfaceHolder.Callback {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var surfaceView: SurfaceView
    private val health = object : Runnable {
        override fun run() {
            if (!MirrorEngine.connected) finish() else handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this).also { it.holder.addCallback(this) }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        val badge = TextView(this).apply {
            text = "MN Cast  •  Android"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            textSize = 16f
            setPadding(24, 12, 24, 12)
            alpha = .9f
        }
        val badgeLp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(28, 28, 0, 0) }
        root.addView(badge, badgeLp)
        setContentView(root)
        badge.animate().alpha(0f).setStartDelay(2500).setDuration(800).start()
        handler.post(health)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = MirrorEngine.attachSurface(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) = MirrorEngine.detachSurface(holder.surface)

    override fun onBackPressed() {
        MirrorEngine.stop()
        super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacks(health)
        super.onDestroy()
    }
}
