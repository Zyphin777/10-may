package com.example.offlineroutingapp

import android.content.Context
import android.graphics.*
import android.location.Location
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.offlineroutingapp.location.NearbyUser
import kotlin.math.*

/**
 * A fully offline peer map. It does not download map tiles.
 * It draws the current device in the center and places nearby users around it
 * according to their relative distance and direction.
 */
class OfflinePeerMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onPeerClicked: ((String) -> Unit)? = null

    private var myLocation: Location? = null
    private var myDisplayName: String = "Me"
    private val peers = linkedMapOf<String, NearbyUser>()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val markerPositions = mutableMapOf<String, PointF>()

    init {
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, 1200f,
            Color.parseColor("#F7F3FF"),
            Color.parseColor("#ECE6FA"),
            Shader.TileMode.CLAMP
        )

        gridPaint.color = Color.parseColor("#DDD3F2")
        gridPaint.strokeWidth = dp(1f)
        gridPaint.alpha = 130

        ringPaint.color = Color.parseColor("#B9A7E7")
        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = dp(1.2f)
        ringPaint.alpha = 170

        linePaint.color = Color.parseColor("#9A82D8")
        linePaint.strokeWidth = dp(1.2f)
        linePaint.alpha = 120

        textPaint.color = Color.parseColor("#3A2C5A")
        textPaint.textAlign = Paint.Align.CENTER

        markerStrokePaint.color = Color.WHITE
        markerStrokePaint.style = Paint.Style.STROKE
        markerStrokePaint.strokeWidth = dp(3f)
    }

    fun setMyLocation(location: Location?, displayName: String = "Me") {
        myLocation = location
        myDisplayName = displayName.ifBlank { "Me" }
        invalidate()
    }

    fun updatePeer(user: NearbyUser) {
        peers[user.nodeId] = user
        invalidate()
    }

    fun removePeer(nodeId: String) {
        peers.remove(nodeId)
        markerPositions.remove(nodeId)
        invalidate()
    }

    fun clearPeers() {
        peers.clear()
        markerPositions.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        markerPositions.clear()

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawGrid(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val safeRadius = min(width, height) * 0.36f

        drawRangeRings(canvas, cx, cy, safeRadius)

        val currentLocation = myLocation
        if (currentLocation == null) {
            drawWaitingState(canvas, cx, cy)
            return
        }

        val maxDistance = calculateMaxDistance(currentLocation).coerceAtLeast(120.0)
        val mapRadiusMeters = chooseMapRadius(maxDistance)

        peers.values.forEach { peer ->
            val point = geoToPoint(currentLocation, peer.latitude, peer.longitude, cx, cy, safeRadius, mapRadiusMeters)
            markerPositions[peer.nodeId] = point
            canvas.drawLine(cx, cy, point.x, point.y, linePaint)
        }

        peers.values.forEach { peer ->
            val point = markerPositions[peer.nodeId] ?: return@forEach
            val distance = distanceMeters(currentLocation.latitude, currentLocation.longitude, peer.latitude, peer.longitude)
            drawPeerMarker(canvas, point.x, point.y, peer.displayName, distance)
        }

        drawMyMarker(canvas, cx, cy)
        drawScale(canvas, mapRadiusMeters)
        drawLegend(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val touchX = event.x
        val touchY = event.y
        val hitRadius = dp(34f)
        val hit = markerPositions.entries.firstOrNull { (_, p) ->
            hypot((touchX - p.x).toDouble(), (touchY - p.y).toDouble()) <= hitRadius
        }
        if (hit != null) {
            onPeerClicked?.invoke(hit.key)
            return true
        }
        return true
    }

    private fun drawGrid(canvas: Canvas) {
        val step = dp(48f)
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }
    }

    private fun drawRangeRings(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius / 3f, ringPaint)
        canvas.drawCircle(cx, cy, radius * 2f / 3f, ringPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)

        textPaint.textSize = sp(11f)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("Offline peer map", cx, dp(28f), textPaint)
    }

    private fun drawWaitingState(canvas: Canvas, cx: Float, cy: Float) {
        drawMyMarker(canvas, cx, cy)
        textPaint.textSize = sp(13f)
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("Waiting for your location...", cx, cy + dp(64f), textPaint)
    }

    private fun drawMyMarker(canvas: Canvas, cx: Float, cy: Float) {
        val radius = dp(25f)
        markerPaint.color = Color.parseColor("#673AB7")
        markerPaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius, markerPaint)
        canvas.drawCircle(cx, cy, radius, markerStrokePaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = sp(13f)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(initials(myDisplayName), cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)

        textPaint.color = Color.parseColor("#3A2C5A")
        textPaint.textSize = sp(11f)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("You", cx, cy + radius + dp(16f), textPaint)
    }

    private fun drawPeerMarker(canvas: Canvas, x: Float, y: Float, name: String, distanceMeters: Double) {
        val radius = dp(22f)
        markerPaint.color = pickColor(name)
        markerPaint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, radius, markerPaint)
        canvas.drawCircle(x, y, radius, markerStrokePaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = sp(12f)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(initials(name), x, y - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)

        textPaint.color = Color.parseColor("#2D2147")
        textPaint.textSize = sp(10f)
        textPaint.typeface = Typeface.DEFAULT_BOLD
        val shortName = if (name.length > 10) name.take(9) + "…" else name
        canvas.drawText(shortName, x, y + radius + dp(13f), textPaint)

        textPaint.color = Color.parseColor("#6E5F8F")
        textPaint.textSize = sp(9f)
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText(formatDistance(distanceMeters), x, y + radius + dp(27f), textPaint)
    }

    private fun drawScale(canvas: Canvas, radiusMeters: Double) {
        val label = "Outer ring ≈ ${formatDistance(radiusMeters)}"
        textPaint.color = Color.parseColor("#6E5F8F")
        textPaint.textSize = sp(11f)
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, dp(16f), height - dp(22f), textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawLegend(canvas: Canvas) {
        textPaint.color = Color.parseColor("#6E5F8F")
        textPaint.textSize = sp(10f)
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText("No internet / no map tiles required", width / 2f, height - dp(22f), textPaint)
    }

    private fun calculateMaxDistance(current: Location): Double {
        return peers.values.maxOfOrNull {
            distanceMeters(current.latitude, current.longitude, it.latitude, it.longitude)
        } ?: 120.0
    }

    private fun chooseMapRadius(maxDistance: Double): Double {
        val candidates = listOf(100.0, 250.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)
        return candidates.firstOrNull { it >= maxDistance * 1.15 } ?: maxDistance * 1.25
    }

    private fun geoToPoint(
        origin: Location,
        lat: Double,
        lng: Double,
        cx: Float,
        cy: Float,
        pixelRadius: Float,
        mapRadiusMeters: Double
    ): PointF {
        // Use Android's geodesic distance + initial bearing instead of a rough
        // meters-per-degree approximation. This makes relative peer placement
        // more accurate, especially when users are not exactly east/west/north/south.
        val results = FloatArray(2)
        Location.distanceBetween(origin.latitude, origin.longitude, lat, lng, results)
        val distanceMeters = results[0].toDouble()
        val bearingRadians = Math.toRadians(results[1].toDouble())
        val scale = pixelRadius / mapRadiusMeters

        val x = cx + (sin(bearingRadians) * distanceMeters * scale).toFloat()
        val y = cy - (cos(bearingRadians) * distanceMeters * scale).toFloat()
        return PointF(
            x.coerceIn(dp(36f), width - dp(36f)),
            y.coerceIn(dp(70f), height - dp(70f))
        )
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    private fun formatDistance(meters: Double): String {
        return when {
            meters < 10 -> "${String.format("%.1f", meters)} m"
            meters < 1000 -> "${String.format("%.0f", meters)} m"
            else -> "${String.format("%.2f", meters / 1000)} km"
        }
    }

    private fun initials(name: String): String {
        return name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "?" }
    }

    private fun pickColor(name: String): Int {
        val colours = intArrayOf(
            Color.parseColor("#F44336"), Color.parseColor("#E91E63"),
            Color.parseColor("#9C27B0"), Color.parseColor("#2196F3"),
            Color.parseColor("#009688"), Color.parseColor("#FF5722"),
            Color.parseColor("#795548"), Color.parseColor("#607D8B")
        )
        return colours[abs(name.hashCode()) % colours.size]
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
