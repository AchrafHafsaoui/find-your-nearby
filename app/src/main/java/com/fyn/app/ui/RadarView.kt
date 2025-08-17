package com.fyn.app.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

data class RadarPeer(
    val address: String,
    val rssi: Int,
    val angleRad: Float,     // fixed angle for the session (0..2π)
    val hasAliases: Boolean
)

class RadarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onDotClick: ((String) -> Unit)? = null

    private val peers = mutableListOf<RadarPeer>()

    // Paints
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C242D")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F6FFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 60
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
        alpha = 60
    }

    private val tmpPts = FloatArray(2)
    private var lastW = 0
    private var lastH = 0

    // RSSI → radius mapping parameters (tweak if you like)
    private val RSSI_NEAR = -50
    private val RSSI_FAR  = -100

    // dp helpers
    private val density get() = resources.displayMetrics.density
    private fun dp(x: Int) = (x * density).toInt()

    private val dotRadiusPx get() = dp(6).toFloat()

    fun setPeers(newPeers: List<RadarPeer>) {
        peers.clear()
        peers.addAll(newPeers)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lastW = w; lastH = h
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - dp(12)

        // Radar rings
        for (i in 1..4) {
            canvas.drawCircle(cx, cy, radius * (i / 4f), ringPaint)
        }

        // Dots
        peers.forEach { p ->
            val rNorm = mapRssiToNorm(p.rssi)  // 0..1 (0=centre, 1=edge)
            val r = rNorm * radius
            val x = cx + r * cos(p.angleRad)
            val y = cy + r * sin(p.angleRad)

            // color: with-aliases = purple, else grey
            dotPaint.color = if (p.hasAliases) Color.parseColor("#7C4DFF") else Color.parseColor("#8B949E")
            canvas.drawCircle(x, y, dotRadiusPx, dotPaint)
            canvas.drawCircle(x, y, dotRadiusPx, dotStroke)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - dp(12)

        // Hit test nearest dot
        var best: Pair<RadarPeer, Float>? = null
        peers.forEach { p ->
            val rNorm = mapRssiToNorm(p.rssi)
            val r = rNorm * radius
            val x = cx + r * cos(p.angleRad)
            val y = cy + r * sin(p.angleRad)
            val d = hypot(event.x - x, event.y - y)
            if (d <= dotRadiusPx * 1.6f) {
                if (best == null || d < best!!.second) best = p to d
            }
        }
        best?.first?.let { onDotClick?.invoke(it.address) }
        return true
    }

    private fun mapRssiToNorm(rssi: Int): Float {
        val clamped = rssi.coerceIn(RSSI_FAR, RSSI_NEAR)
        val t = (RSSI_NEAR - clamped).toFloat() / (RSSI_NEAR - RSSI_FAR).toFloat()
        // keep strong signals near centre but never exactly on 0
        return (0.12f + 0.88f * t).coerceIn(0f, 1f)
    }
}
