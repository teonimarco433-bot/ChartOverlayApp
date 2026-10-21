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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
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
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
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
        backgroundThread = HandlerThread("CaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        mp?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                ir?.close()
                mp = null
            }
        }, Handler(Looper.getMainLooper()))

        mp?.createVirtualDisplay("QuantCapture", w, h, dm.densityDpi, 
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir?.surface, null, backgroundHandler)

        ir?.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * w

                // Correzione critica per GPU Samsung: gestione del padding di riga
                val paddedBitmap = Bitmap.createBitmap(
                    w + rowPadding / pixelStride,
                    h,
                    Bitmap.Config.ARGB_8888
                )
                paddedBitmap.copyPixelsFromBuffer(buffer)

                val cleanBitmap = if (rowPadding == 0) {
                    paddedBitmap
                } else {
                    Bitmap.createBitmap(paddedBitmap, 0, 0, w, h)
                }

                CandleAnalysisEngine.processFrame(cleanBitmap)
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, backgroundHandler)
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "QuantScanner", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        return Notification.Builder(this, CH_ID)
            .setContentTitle("QuantOverlay Attivo")
            .setContentText("Cattura frame attiva")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        backgroundThread?.quitSafely()
        ir?.close()
        mp?.stop()
        super.onDestroy()
    }
}
