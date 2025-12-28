package fansirsqi.xposed.sesame.util

import android.content.Context
import android.util.Log
import fansirsqi.xposed.sesame.util.GlobalThreadPools.sleepCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 贝塞尔曲线滑动工具类
 * 使用三次贝塞尔曲线模拟人类真实的滑动轨迹，避免被检测为机器行为
 */
object BezierSwipeUtil {

    private const val TAG = "BezierSwipe"

    private const val BASE_DURATION = 300L
    private const val POINT_COUNT = 20

    private const val CONTROL_POINT_OFFSET_RATIO = 0.3
    private const val CONTROL_POINT_Y_VARIATION = 50
    private const val POINT_X_VARIATION = 3
    private const val POINT_Y_VARIATION = 2

    private const val SPEED_FACTOR_START = 0.6
    private const val SPEED_FACTOR_MIDDLE = 1.3
    private const val SPEED_FACTOR_END = 0.5

    private const val PAUSE_PROBABILITY = 0.15
    private const val PAUSE_DURATION_MIN = 30L
    private const val PAUSE_DURATION_MAX = 80L

    private const val DURATION_VARIATION = 50L

    data class Point(val x: Int, val y: Int)

    data class SwipePathPoint(val point: Point, val delay: Long)

    data class SwipePath(val points: List<SwipePathPoint>, val totalDuration: Long)

    private fun generateRandomOffset(base: Double, variation: Double): Double {
        return base + (Random.nextDouble() - 0.5) * variation
    }

    private fun calculateDistance(p1: Point, p2: Point): Double {
        val dx = (p2.x - p1.x).toDouble()
        val dy = (p2.y - p1.y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    private fun calculateTotalDistance(points: List<Point>): Double {
        var totalDistance = 0.0
        for (i in 0 until points.size - 1) {
            totalDistance += calculateDistance(points[i], points[i + 1])
        }
        return totalDistance
    }

    private fun cubicBezierPoint(t: Double, p0: Point, p1: Point, p2: Point, p3: Point): Point {
        val mt = 1 - t
        val mt2 = mt * mt
        val mt3 = mt2 * mt
        val t2 = t * t
        val t3 = t2 * t

        val x = (mt3 * p0.x + 3 * mt2 * t * p1.x + 3 * mt * t2 * p2.x + t3 * p3.x).toInt()
        val y = (mt3 * p0.y + 3 * mt2 * t * p1.y + 3 * mt * t2 * p2.y + t3 * p3.y).toInt()

        return Point(x, y)
    }

    private fun generateControlPoints(start: Point, end: Point): Pair<Point, Point> {
        val distance = calculateDistance(start, end)
        val controlOffset = distance * CONTROL_POINT_OFFSET_RATIO

        val cp1X = (start.x + generateRandomOffset(controlOffset, controlOffset * 0.3)).toInt().coerceIn(start.x + 10, end.x - 10)
        val cp1Y = (start.y + generateRandomOffset(0.0, CONTROL_POINT_Y_VARIATION.toDouble())).toInt()

        val cp2X = (end.x - generateRandomOffset(controlOffset, controlOffset * 0.3)).toInt().coerceIn(start.x + 10, end.x - 10)
        val cp2Y = (end.y + generateRandomOffset(0.0, CONTROL_POINT_Y_VARIATION.toDouble())).toInt()

        return Pair(Point(cp1X, cp1Y), Point(cp2X, cp2Y))
    }

    private fun generateBezierPath(start: Point, end: Point): List<Point> {
        val (cp1, cp2) = generateControlPoints(start, end)
        val points = mutableListOf<Point>()

        for (i in 0 until POINT_COUNT) {
            val t = i.toDouble() / (POINT_COUNT - 1)
            val point = cubicBezierPoint(t, start, cp1, cp2, end)
            points.add(point)
        }

        return points
    }

    private fun addRandomJitter(points: List<Point>): List<Point> {
        return points.map { point ->
            Point(
                (point.x + generateRandomOffset(0.0, POINT_X_VARIATION.toDouble())).toInt(),
                (point.y + generateRandomOffset(0.0, POINT_Y_VARIATION.toDouble())).toInt()
            )
        }
    }

    private fun calculateSpeedFactor(index: Int, total: Int): Double {
        val progress = index.toDouble() / total
        return when {
            progress < 0.3 -> SPEED_FACTOR_START
            progress < 0.7 -> SPEED_FACTOR_MIDDLE
            else -> SPEED_FACTOR_END
        }
    }

    private fun generateSwipePathPoints(points: List<Point>, baseDuration: Long): List<SwipePathPoint> {
        val totalDistance = calculateTotalDistance(points)
        val pathPoints = mutableListOf<SwipePathPoint>()
        var accumulatedDelay = 0L

        for (i in 0 until points.size - 1) {
            val segmentDistance = calculateDistance(points[i], points[i + 1])
            val speedFactor = calculateSpeedFactor(i, points.size - 1)
            val segmentDuration = ((segmentDistance / totalDistance) * baseDuration * speedFactor).toLong()

            val shouldPause = Random.nextDouble() < PAUSE_PROBABILITY && i > 0 && i < points.size - 2
            val pauseDelay = if (shouldPause) {
                Random.nextLong(PAUSE_DURATION_MIN, PAUSE_DURATION_MAX)
            } else {
                0L
            }

            pathPoints.add(SwipePathPoint(points[i], segmentDuration + pauseDelay))
            accumulatedDelay += segmentDuration + pauseDelay
        }

        pathPoints.add(SwipePathPoint(points.last(), 0L))

        return pathPoints
    }

    private fun generateSwipePath(startX: Int, startY: Int, endX: Int, endY: Int): SwipePath {
        val start = Point(startX, startY)
        val end = Point(endX, endY)

        val bezierPoints = generateBezierPath(start, end)
        val jitteredPoints = addRandomJitter(bezierPoints)

        val durationVariation = Random.nextLong(-DURATION_VARIATION, DURATION_VARIATION)
        val actualDuration = BASE_DURATION + durationVariation

        val pathPoints = generateSwipePathPoints(jitteredPoints, actualDuration)
        val totalDuration = pathPoints.sumOf { it.delay }

        return SwipePath(pathPoints, totalDuration)
    }

    suspend fun bezierSwipe(
        context: Context,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        duration: Long = BASE_DURATION
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "开始贝塞尔曲线滑动: ($startX, $startY) -> ($endX, $endY)")

            val swipePath = generateSwipePath(startX, startY, endX, endY)

            Log.d(TAG, "生成路径点数量: ${swipePath.points.size}, 总耗时: ${swipePath.totalDuration}ms")

            for (i in 0 until swipePath.points.size - 1) {
                val currentPoint = swipePath.points[i]
                val nextPoint = swipePath.points[i + 1]

                val command = "input swipe ${currentPoint.point.x} ${currentPoint.point.y} ${nextPoint.point.x} ${nextPoint.point.y} ${currentPoint.delay}"

                val success = SwipeUtil.execShizukuCommandOriginal(context, command)
                if (!success) {
                    Log.e(TAG, "滑动命令执行失败")
                    return@withContext false
                }

                if (currentPoint.delay > 0) {
                    sleepCompat(currentPoint.delay)
                }
            }

            Log.d(TAG, "贝塞尔曲线滑动完成")
            true
        } catch (e: Exception) {
            Log.e(TAG, "贝塞尔曲线滑动异常: ${e.message}")
            false
        }
    }

    fun logPathInfo(startX: Int, startY: Int, endX: Int, endY: Int) {
        val start = Point(startX, startY)
        val end = Point(endX, endY)
        val (cp1, cp2) = generateControlPoints(start, end)
        Log.d(TAG, "起点: ($startX, $startY)")
        Log.d(TAG, "控制点1: (${cp1.x}, ${cp1.y})")
        Log.d(TAG, "控制点2: (${cp2.x}, ${cp2.y})")
        Log.d(TAG, "终点: ($endX, $endY)")
    }
}
