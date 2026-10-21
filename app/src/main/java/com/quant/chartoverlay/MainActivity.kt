package com.quant.chartoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.quant.chartoverlay.core.ScreenCaptureService
import com.quant.chartoverlay.overlay.FloatingViewService

class MainActivity : Activity() {
    companion object {
        private const val OVERLAY_REQ = 1001
        private const val PROJECTION_REQ = 1002
    }

    private lateinit var mgr: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                OVERLAY_REQ
            )
        } else {
            startActivityForResult(mgr.createScreenCaptureIntent(), PROJECTION_REQ)
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == OVERLAY_REQ && Settings.canDrawOverlays(this)) {
            startActivityForResult(mgr.createScreenCaptureIntent(), PROJECTION_REQ)
        } else if (req == PROJECTION_REQ && res == RESULT_OK && data != null) {
            val svc = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, res)
                putExtra(ScreenCaptureService.EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
            startService(Intent(this, FloatingViewService::class.java))
            finish()
        }
    }
}
