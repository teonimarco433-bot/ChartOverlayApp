package com.quant.chartoverlay.core

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max

enum class SignalType {
    SCANNING,
    ESAURIMENTO_BUY,
    ESAURIMENTO_SELL,
    RIASSORBIMENTO_BUY,
    RIASSORBIMENTO_SELL
}

data class TelemetryState(
    val currentPrice: String,
    val signal: SignalType,
    val wickRatio: Float
)

object CandleAnalysisEngine {
    private val _telemetry = MutableSharedFlow<TelemetryState>(extraBufferCapacity = 1)
    val telemetry: SharedFlow<TelemetryState> = _telemetry.asSharedFlow()

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val pricePattern = Pattern.compile("\\b\\d+[.,]\\d{2,6}\\b")

    private var cachedPrice = "N/D"
    private var lastOcrTimestamp = 0L
    private val OCR_INTERVAL_MS = 250L // Scansione OCR 4 volte al secondo per non saturare CPU

    fun processFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()

        // 1. OCR Estrazione Prezzo Asse Destro (ultimi 18% larghezza)
        if (now - lastOcrTimestamp > OCR_INTERVAL_MS) {
            lastOcrTimestamp = now
            extractRealtimePrice(bitmap)
        }

        // 2. Analisi Candele Pixel & Riassorbimenti
        val (signal, ratio) = evaluateCandleStructure(bitmap)

        _telemetry.tryEmit(TelemetryState(cachedPrice, signal, ratio))
    }

    private fun extractRealtimePrice(b: Bitmap) {
        try {
            val axisStartX = (b.width * 0.82f).toInt()
            val axisWidth = b.width - axisStartX
            val croppedAxis = Bitmap.createBitmap(b, axisStartX, 0, axisWidth, b.height)

            val inputImage = InputImage.fromBitmap(croppedAxis, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    for (block in visionText.textBlocks) {
                        val matcher = pricePattern.matcher(block.text)
                        if (matcher.find()) {
                            cachedPrice = matcher.group()
                            break
                        }
                    }
                }
        } catch (_: Exception) {}
    }

    private fun evaluateCandleStructure(b: Bitmap): Pair<SignalType, Float> {
        val width = b.width
        val height = b.height
        val startX = (width * 0.70f).toInt()
        val endX = (width * 0.84f).toInt()

        for (x in endX downTo startX step 3) {
            var highestY = -1
            var lowestY = -1
            var bodyTopY = -1
            var bodyBottomY = -1

            for (y in 60 until height - 60 step 2) {
                val pixel = b.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val bl = Color.blue(pixel)

                val isGreen = g > 140 && r < 120 && bl < 120
                val isRed = r > 150 && g < 110 && bl < 110

                if (isGreen || isRed) {
                    if (highestY == -1) highestY = y
                    lowestY = y
                    if (bodyTopY == -1) bodyTopY = y
                    bodyBottomY = y
                }
            }

            if (highestY != -1 && lowestY != -1) {
                val totalRange = lowestY - highestY
                if (totalRange >= 20) {
                    val bodyHeight = max(1, bodyBottomY - bodyTopY)
                    val upperWick = bodyTopY - highestY
                    val lowerWick = lowestY - bodyBottomY

                    val upperRatio = upperWick.toFloat() / bodyHeight.toFloat()
                    val lowerRatio = lowerWick.toFloat() / bodyHeight.toFloat()

                    // Riassorbimento Sell: sweep massimi con forte wick reiezione (Ratio >= 3.0)
                    if (upperRatio >= 3.0f && upperWick >= (totalRange * 0.60f)) {
                        return Pair(SignalType.RIASSORBIMENTO_SELL, upperRatio)
                    }

                    // Riassorbimento Buy: sweep minimi con forte wick reiezione (Ratio >= 3.0)
                    if (lowerRatio >= 3.0f && lowerWick >= (totalRange * 0.60f)) {
                        return Pair(SignalType.RIASSORBIMENTO_BUY, lowerRatio)
                    }

                    // Esaurimento Sell standard (Ratio >= 2.2)
                    if (upperRatio >= 2.2f && upperWick >= (totalRange * 0.50f)) {
                        return Pair(SignalType.ESAURIMENTO_SELL, upperRatio)
                    }

                    // Esaurimento Buy standard (Ratio >= 2.2)
                    if (lowerRatio >= 2.2f && lowerWick >= (totalRange * 0.50f)) {
                        return Pair(SignalType.ESAURIMENTO_BUY, lowerRatio)
                    }
                }
            }
        }
        return Pair(SignalType.SCANNING, 0.0f)
    }
}
