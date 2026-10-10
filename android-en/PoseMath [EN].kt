package com.example.ets2headtracker

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

data class Pt(val x: Float, val y: Float)

class OneEuro(var minCutoff: Float, var beta: Float, private val dCutoff: Float = 1f) {
    private var xPrev = 0f
    private var dxPrev = 0f
    private var ready = false

    fun reset() {
        ready = false
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    fun filter(x: Float, dt: Float): Float {
        if (!ready) {
            ready = true
            xPrev = x
            dxPrev = 0f
            return x
        }
        val d = (x - xPrev) / dt
        val ad = alpha(dCutoff, dt)
        val dHat = ad * d + (1f - ad) * dxPrev
        val a = alpha(minCutoff + beta * abs(dHat), dt)
        val xHat = a * x + (1f - a) * xPrev
        xPrev = xHat
        dxPrev = dHat
        return xHat
    }
}

object Curve {
    fun eval(p: List<Pt>, x: Float): Float {
        val n = p.size
        if (n == 0) return x
        if (n == 1 || x <= p[0].x) return p[0].y
        if (x >= p[n - 1].x) return p[n - 1].y

        val d = FloatArray(n - 1) { (p[it + 1].y - p[it].y) / (p[it + 1].x - p[it].x) }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (k in 1 until n - 1) m[k] = if (d[k - 1] * d[k] <= 0f) 0f else (d[k - 1] + d[k]) / 2f
        for (k in 0 until n - 1) {
            if (d[k] == 0f) {
                m[k] = 0f
                m[k + 1] = 0f
            } else {
                val a = m[k] / d[k]
                val b = m[k + 1] / d[k]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    m[k] = t * a * d[k]
                    m[k + 1] = t * b * d[k]
                }
            }
        }

        var k = 0
        while (k < n - 2 && x > p[k + 1].x) k++
        val h = p[k + 1].x - p[k].x
        val t = (x - p[k].x) / h
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * p[k].y +
            (t3 - 2 * t2 + t) * h * m[k] +
            (-2 * t3 + 3 * t2) * p[k + 1].y +
            (t3 - t2) * h * m[k + 1]
    }

    fun map(p: List<Pt>, v: Float): Float {
        val y = eval(p, abs(v))
        return if (v < 0f) -y else y
    }
}

object PoseMath {
    class Frame(val r: FloatArray, val t: FloatArray)

    fun parse(m: FloatArray): Frame? {
        if (m.size < 16) return null
        val colMajor = abs(m[3]) + abs(m[7]) + abs(m[11]) <= abs(m[12]) + abs(m[13]) + abs(m[14])
        val r = FloatArray(9)
        val t = FloatArray(3)
        for (row in 0..2) for (col in 0..2) {
            r[row * 3 + col] = if (colMajor) m[col * 4 + row] else m[row * 4 + col]
        }
        for (i in 0..2) t[i] = if (colMajor) m[12 + i] else m[i * 4 + 3]
        for (col in 0..2) {
            val n = sqrt(r[col] * r[col] + r[3 + col] * r[3 + col] + r[6 + col] * r[6 + col])
            if (n < 1e-6f) return null
            for (row in 0..2) r[row * 3 + col] /= n
        }
        return Frame(r, t)
    }

    fun relative(ref: Frame, cur: Frame): FloatArray {
        val rel = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0f
            for (k in 0..2) s += ref.r[k * 3 + i] * cur.r[k * 3 + j]
            rel[i * 3 + j] = s
        }
        val pitch = asin((-rel[5]).coerceIn(-1f, 1f))
        val yaw = atan2(rel[2], rel[8])
        val roll = atan2(rel[3], rel[4])
        return floatArrayOf(
            Math.toDegrees(yaw.toDouble()).toFloat(),
            Math.toDegrees(pitch.toDouble()).toFloat(),
            Math.toDegrees(roll.toDouble()).toFloat(),
            cur.t[0] - ref.t[0],
            cur.t[1] - ref.t[1],
            cur.t[2] - ref.t[2],
        )
    }
}
