package com.quant.chartoverlay.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class SignalType { NONE, EXHAUSTION_BUY, EXHAUSTION_SELL }

object CandleAnalysisEngine {
    private val _signals = MutableSharedFlow<SignalType>(extraBufferCapacity = 1)
    val signals: SharedFlow<SignalType> = _signals.asSharedFlow()

    // Configurazione Soglie Matematiche
    private const val MIN_CANDLE_HEIGHT_PX = 25       // Altezza minima candela per evitare rumore
    private const val EXHAUSTION_RATIO = 2.0          // Rapporto minimo stoppino / corpo
    private const val WICK_DOMINANCE_PCT = 0.50       // Lo stoppino deve coprire almeno il 50% del range

    // Limiti scansione orizzontale (ultimi 25% a destra dello schermo)
    private const val ROI_START_X_PCT = 0.75f
    private const val ROI_END_X_PCT = 0.95f

    private var lastEmittedSignal = SignalType.NONE

    fun processFrame(bitmap: Bitmap) {
        val signal = evaluate(bitmap)
        if (signal != lastEmittedSignal) {
            lastEmittedSignal = signal
            _signals.tryEmit(signal)
        }
    }

    private fun evaluate(b: Bitmap): SignalType {
        val width = b.width
        val height = b.height

        val startX = (width * ROI_START_X_PCT).toInt()
        val endX = (width * ROI_END_X_PCT).toInt()

        var latestCandleSignal = SignalType.NONE

        // Scansione da destra verso sinistra per trovare la candela più recente attiva
        for (x in endX downTo startX step 4) {
            var highestY = -1
            var lowestY = -1
            var bodyTopY = -1
            var bodyBottomY = -1

            var isBullish = false
            var isBearish = false

            // Scansione verticale della colonna
            for (y in 50 until height - 50 step 2) {
                val pixel = b.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val bColor = Color.blue(pixel)

                // Rilevamento colore candela verde (Buy) o rossa (Sell)
                val isGreen = g > 150 && r < 120 && bColor < 120
                val isRed = r > 160 && g < 110 && bColor < 110

                if (isGreen || isRed) {
                    if (highestY == -1) highestY = y
                    lowestY = y

                    // Rilevamento corpo (larghezza minima pixel)
                    if (isGreen) isBullish = true
                    if (isRed) isBearish = true

                    if (bodyTopY == -1) bodyTopY = y
                    bodyBottomY = y
                }
            }

            // Validazione se abbiamo trovato una struttura candela valida
            if (highestY != -1 && lowestY != -1) {
                val totalRange = lowestY - highestY
                if (totalRange >= MIN_CANDLE_HEIGHT_PX) {
                    val bodyHeight = max(1, bodyBottomY - bodyTopY)
                    val upperWick = bodyTopY - highestY
                    val lowerWick = lowestY - bodyBottomY

                    // Condizione Esaurimento Sell (Shooting Star / Upper Rejection)
                    if (upperWick >= (bodyHeight * EXHAUSTION_RATIO) && 
                        upperWick >= (totalRange * WICK_DOMINANCE_PCT)) {
                        latestCandleSignal = SignalType.EXHAUSTION_SELL
                        break
                    }

                    // Condizione Esaurimento Buy (Hammer / Lower Rejection)
                    if (lowerWick >= (bodyHeight * EXHAUSTION_RATIO) && 
                        lowerWick >= (totalRange * WICK_DOMINANCE_PCT)) {
                        latestCandleSignal = SignalType.EXHAUSTION_BUY
                        break
                    }
                }
            }
        }

        return latestCandleSignal
    }
}
