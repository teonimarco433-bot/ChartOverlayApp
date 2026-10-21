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
import com.quant.chartoverlay.core.SignalType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class FloatingViewService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var container: LinearLayout
    private lateinit var priceView: TextView
    private lateinit var signalView: TextView
    private lateinit var ratioView: TextView
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
            x = 80
            y = 150
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6121212")) // Nero opaco 90%
            setPadding(35, 25, 35, 25)
        }

        priceView = TextView(this).apply {
            text = "PREZZO: ATTESA..."
            setTextColor(Color.WHITE)
            textSize = 14f
        }

        signalView = TextView(this).apply {
            text = "STATO: SCANNING..."
            setTextColor(Color.CYAN)
            textSize = 15f
            paint.isFakeBoldText = true
        }

        ratioView = TextView(this).apply {
            text = "WICK RATIO: --"
            setTextColor(Color.LTGRAY)
            textSize = 12f
        }

        container.addView(priceView)
        container.addView(signalView)
        container.addView(ratioView)

        // Drag & drop
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
                priceView.text = "LIVE: ${t.currentPrice}"
                ratioView.text = if (t.wickRatio > 0) "WICK/BODY: ${String.format("%.1fx", t.wickRatio)}" else "WICK/BODY: --"

                when (t.signal) {
                    SignalType.RIASSORBIMENTO_BUY -> {
                        signalView.text = "RIASSORBIMENTO BUY"
                        signalView.setTextColor(Color.parseColor("#00E676")) // Verde Smeraldo Forte
                    }
                    SignalType.RIASSORBIMENTO_SELL -> {
                        signalView.text = "RIASSORBIMENTO SELL"
                        signalView.setTextColor(Color.parseColor("#FF1744")) // Rosso Acceso
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
                        signalView.text = "SCANNING..."
                        signalView.setTextColor(Color.CYAN)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wm.removeView(container)
        super.onDestroy()
    }
}
