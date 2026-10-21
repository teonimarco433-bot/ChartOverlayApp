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
import kotlin.math.max

enum class SignalType {
    SCANNING,
    ESAURIMENTO_BUY,
    ESAURIMENTO_SELL,
    RIASSORBIMENTO_BUY,
    RIASSORBIMENTO_SELL
}

data class TelemetryState(
    val frameId: Long,
    val currentPrice: String,
    val candleColor: String,
    val candleHeightPx: Int,
    val wickRatio: Float,
    val signal: SignalType
)

object CandleAnalysisEngine {
    private val _telemetry = MutableSharedFlow<TelemetryState>(extraBufferCapacity = 1)
    val telemetry: SharedFlow<TelemetryState> = _telemetry.asSharedFlow()

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val pricePattern = Pattern.compile("(\\d{1,6}[.,]\\d{2,6})")

    private var frameCounter = 0L
    private var cachedPrice = "RICERCA ASSE..."
    private var lastOcrTime = 0L
    private const val OCR_INTERVAL_MS = 150L

    fun processFrame(bitmap: Bitmap) {
        frameCounter++
        val now = System.currentTimeMillis()

        // 1. OCR Asse Prezzi (ultimi 25% della larghezza schermo)
        if (now - lastOcrTime > OCR_INTERVAL_MS) {
            lastOcrTime = now
            scanPriceAxis(bitmap)
        }

        // 2. Analisi Candele
        val candleMetrics = analyzeLatestCandle(bitmap)

        _telemetry.tryEmit(
            TelemetryState(
                frameId = frameCounter,
                currentPrice = cachedPrice,
                candleColor = candleMetrics.colorName,
                candleHeightPx = candleMetrics.heightPx,
                wickRatio = candleMetrics.ratio,
                signal = candleMetrics.signal
            )
        )
    }

    private fun scanPriceAxis(b: Bitmap) {
        try {
            val startX = (b.width * 0.72f).toInt()
            val axisWidth = b.width - startX
            val cropped = Bitmap.createBitmap(b, startX, 0, axisWidth, b.height)

            val inputImage = InputImage.fromBitmap(cropped, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { textResult ->
                    var foundPrice = ""
                    for (block in textResult.textBlocks) {
                        val m = pricePattern.matcher(block.text)
                        if (m.find()) {
                            foundPrice = m.group(1) ?: ""
                            break
                        }
                    }
                    if (foundPrice.isNotEmpty()) {
                        cachedPrice = foundPrice
                    }
                }
        } catch (_: Exception) {}
    }

    private data class CandleMetrics(
        val colorName: String,
        val heightPx: Int,
        val ratio: Float,
        val signal: SignalType
    )

    private fun analyzeLatestCandle(b: Bitmap): CandleMetrics {
        val width = b.width
        val height = b.height
        val startX = (width * 0.60f).toInt()
        val endX = (width * 0.80f).toInt()

        for (x in endX downTo startX step 4) {
            var highestY = -1
            var lowestY = -1
            var bodyTopY = -1
            var bodyBottomY = -1
            var greenPixels = 0
            var redPixels = 0

            for (y in 100 until height - 100 step 3) {
                val p = b.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val bl = Color.blue(p)

                val isGreen = g > 130 && r < 120 && bl < 120
                val isRed = r > 150 && g < 110 && bl < 110

                if (isGreen) {
                    greenPixels++
                    if (highestY == -1) highestY = y
                    lowestY = y
                    if (bodyTopY == -1) bodyTopY = y
                    bodyBottomY = y
                } else if (isRed) {
                    redPixels++
                    if (highestY == -1) highestY = y
                    lowestY = y
                    if (bodyTopY == -1) bodyTopY = y
                    bodyBottomY = y
                }
            }

            if (highestY != -1 && lowestY != -1) {
                val totalRange = lowestY - highestY
                if (totalRange >= 15) {
                    val colorName = if (greenPixels >= redPixels) "VERDE" else "ROSSO"
                    val bodyHeight = max(1, bodyBottomY - bodyTopY)
                    val upperWick = bodyTopY - highestY
                    val lowerWick = lowestY - bodyBottomY

                    val upperRatio = upperWick.toFloat() / bodyHeight
                    val lowerRatio = lowerWick.toFloat() / bodyHeight

                    if (upperRatio >= 3.0f && upperWick >= (totalRange * 0.60f)) {
                        return CandleMetrics(colorName, totalRange, upperRatio, SignalType.RIASSORBIMENTO_SELL)
                    }
                    if (lowerRatio >= 3.0f && lowerWick >= (totalRange * 0.60f)) {
                        return CandleMetrics(colorName, totalRange, lowerRatio, SignalType.RIASSORBIMENTO_BUY)
                    }

                    if (upperRatio >= 2.2f && upperWick >= (totalRange * 0.50f)) {
                        return CandleMetrics(colorName, totalRange, upperRatio, SignalType.ESAURIMENTO_SELL)
                    }
                    if (lowerRatio >= 2.2f && lowerWick >= (totalRange * 0.50f)) {
                        return CandleMetrics(colorName, totalRange, lowerRatio, SignalType.ESAURIMENTO_BUY)
                    }

                    return CandleMetrics(colorName, totalRange, max(upperRatio, lowerRatio), SignalType.SCANNING)
                }
            }
        }
        return CandleMetrics("IN CERCA CANDELA", 0, 0f, SignalType.SCANNING)
    }
}
