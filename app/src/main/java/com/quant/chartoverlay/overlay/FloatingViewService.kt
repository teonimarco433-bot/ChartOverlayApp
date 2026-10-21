package com.quant.chartoverlay.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import com.quant.chartoverlay.core.CandleAnalysisEngine
import com.quant.chartoverlay.core.ScreenCaptureService
import com.quant.chartoverlay.core.SignalType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class FloatingViewService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var container: LinearLayout
    private lateinit var heartBeatView: TextView
    private lateinit var priceView: TextView
    private lateinit var candleView: TextView
    private lateinit var signalView: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        initUi()
        observeData()
    }

    private fun initUi() {
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
            x = 60
            y = 120
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE101010")) // Nero 93%
            setPadding(35, 20, 35, 25)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        heartBeatView = TextView(this).apply {
            text = "● LIVE (FRAME #0)"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val closeBtn = TextView(this).apply {
            text = " ✕ "
            setTextColor(Color.RED)
            textSize = 17f
            paint.isFakeBoldText = true
            setPadding(20, 0, 0, 5)
            setOnClickListener { stopSelf() }
        }

        header.addView(heartBeatView)
        header.addView(closeBtn)

        priceView = TextView(this).apply {
            text = "PREZZO: IN ATTESA..."
            setTextColor(Color.WHITE)
            textSize = 15f
            paint.isFakeBoldText = true
            setPadding(0, 5, 0, 3)
        }

        candleView = TextView(this).apply {
            text = "CANDELA: IN SCANSIONE"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(0, 0, 0, 5)
        }

        signalView = TextView(this).apply {
            text = "STATO: SCANNING..."
            setTextColor(Color.CYAN)
            textSize = 14f
            paint.isFakeBoldText = true
        }

        container.addView(header)
        container.addView(priceView)
        container.addView(candleView)
        container.addView(signalView)

        container.setOnTouchListener(object : View.OnTouchListener {
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
                        wm.updateViewLayout(container, params)
                        return true
                    }
                }
                return false
            }
        })

        wm.addView(container, params)
    }

    private fun observeData() {
        scope.launch {
            CandleAnalysisEngine.telemetry.collectLatest { t ->
                heartBeatView.text = "● LIVE (FRAME #${t.frameId})"
                priceView.text = "PREZZO: ${t.currentPrice}"

                val candleColorHex = if (t.candleColor == "VERDE") "#00E676" else if (t.candleColor == "ROSSO") "#FF3D00" else "#AAAAAA"
                candleView.text = "CANDELA: ${t.candleColor} (${t.candleHeightPx}px | ${String.format("%.1fx", t.wickRatio)})"
                candleView.setTextColor(Color.parseColor(candleColorHex))

                when (t.signal) {
                    SignalType.RIASSORBIMENTO_BUY -> {
                        signalView.text = "RIASSORBIMENTO BUY"
                        signalView.setTextColor(Color.parseColor("#00E676"))
                    }
                    SignalType.RIASSORBIMENTO_SELL -> {
                        signalView.text = "RIASSORBIMENTO SELL"
                        signalView.setTextColor(Color.parseColor("#FF1744"))
                    }
                    SignalType.ESAURIMENTO_BUY -> {
                        signalView.text = "ESAURIMENTO BUY"
                        signalView.setTextColor(Color.GREEN)
                    }
                    SignalType.ESAURIMENTO_SELL -> {
                        signalView.text = "ESAURIMENTO SELL"
                        signalView.setTextColor(Color.RED)
                    }
                    SignalType.SCANNING -> {
                        signalView.text = "STATO: SCANNING..."
                        signalView.setTextColor(Color.CYAN)
                    }
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        if (::container.isInitialized) wm.removeView(container)
        stopService(Intent(this, ScreenCaptureService::class.java))
        super.onDestroy()
    }
}
