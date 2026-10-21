package com.quant.chartoverlay.core

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.quant.chartoverlay.R
import kotlinx.coroutines.*

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "RES_CODE"
        const val EXTRA_DATA = "DATA"
        private const val CH_ID = "screen_cap"
        private const val NOTIF_ID = 99
    }

    private var mp: MediaProjection? = null
    private var ir: ImageReader? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (code != 0 && data != null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mp = mgr.getMediaProjection(code, data)
            setupCapture()
        }
        return START_NOT_STICKY
    }

    private fun setupCapture() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels / 2
        val h = dm.heightPixels / 2
        ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        mp?.createVirtualDisplay("Capture", w, h, dm.densityDpi, 
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir?.surface, null, null)

        ir?.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            scope.launch {
                val plane = img.planes[0]
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                img.close()
                CandleAnalysisEngine.processFrame(bmp)
            }
        }, null)
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "QuantScanner", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        return Notification.Builder(this, CH_ID)
            .setContentTitle("QuantOverlay Attivo")
            .setContentText("Cattura frame in esecuzione")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        ir?.close()
        mp?.stop()
        super.onDestroy()
    }
}
