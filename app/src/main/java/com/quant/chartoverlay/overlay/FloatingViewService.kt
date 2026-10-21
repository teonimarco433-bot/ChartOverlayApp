package com.quant.chartoverlay.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import com.quant.chartoverlay.core.CandleAnalysisEngine
import com.quant.chartoverlay.core.SignalType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class FloatingViewService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var hud: FrameLayout
    private lateinit var label: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        hud = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(30, 20, 30, 20)
        }
        label = TextView(this).apply {
            text = "SCANNING..."
            setTextColor(Color.CYAN)
        }
        hud.addView(label)

        hud.setOnTouchListener(object : View.OnTouchListener {
            var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        ix = params.x; iy = params.y
                        tx = e.rawX; ty = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = ix + (e.rawX - tx).toInt()
                        params.y = iy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(hud, params)
                        return true
                    }
                }
                return false
            }
        })

        wm.addView(hud, params)
        scope.launch {
            CandleAnalysisEngine.signals.collectLatest { s ->
                when (s) {
                    SignalType.EXHAUSTION_BUY -> { label.text = "BUY"; label.setTextColor(Color.GREEN) }
                    SignalType.EXHAUSTION_SELL -> { label.text = "SELL"; label.setTextColor(Color.RED) }
                    SignalType.NONE -> { label.text = "SCANNING..."; label.setTextColor(Color.CYAN) }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wm.removeView(hud)
        super.onDestroy()
    }
}
