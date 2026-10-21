package com.quant.chartoverlay.core

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class SignalType { NONE, EXHAUSTION_BUY, EXHAUSTION_SELL }

object CandleAnalysisEngine {
    private val _signals = MutableSharedFlow<SignalType>(extraBufferCapacity = 1)
    val signals: SharedFlow<SignalType> = _signals.asSharedFlow()

    fun processFrame(bitmap: Bitmap) {
        val signal = evaluate(bitmap)
        if (signal != SignalType.NONE) _signals.tryEmit(signal)
    }

    private fun evaluate(b: Bitmap): SignalType {
        return SignalType.NONE
    }
}
