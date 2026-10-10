package com.example.ets2headtracker

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class AxisConfig(
    val name: String,
    val inMax: Float,
    val outMax: Float,
    private val defaults: List<Pt>,
    private val prefs: SharedPreferences,
) {
    val points = mutableStateListOf<Pt>()
    var invert by mutableStateOf(prefs.getBoolean("inv_$name", false))

    init {
        points.addAll(load() ?: defaults)
    }

    private fun load(): List<Pt>? {
        val s = prefs.getString("curve_$name", null) ?: return null
        return try {
            s.split(";").map {
                val (x, y) = it.split(",")
                Pt(x.toFloat().coerceIn(0f, inMax), y.toFloat().coerceIn(0f, outMax))
            }.takeIf { it.size >= 2 }
        } catch (e: Exception) {
            null
        }
    }

    fun save() {
        prefs.edit()
            .putString("curve_$name", points.joinToString(";") { "${it.x},${it.y}" })
            .putBoolean("inv_$name", invert)
            .apply()
    }

    fun resetCurve() {
        points.clear()
        points.addAll(defaults)
        save()
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("headtracker", Context.MODE_PRIVATE)

    var minCutoff by mutableFloatStateOf(prefs.getFloat("minCutoff", 0.8f))
    var beta by mutableFloatStateOf(prefs.getFloat("beta", 0.05f))
    var sendPosition by mutableStateOf(prefs.getBoolean("sendPosition", false))
    var manualEnabled by mutableStateOf(prefs.getBoolean("manualEnabled", false))
    var manualIp by mutableStateOf(prefs.getString("manualIp", "") ?: "")
    var manualPort by mutableIntStateOf(prefs.getInt("manualPort", Protocol.PC_PORT))

    val axes = listOf(
        AxisConfig(
            "Yaw", 90f, 180f,
            listOf(Pt(0f, 0f), Pt(20f, 30f), Pt(45f, 90f), Pt(70f, 150f), Pt(90f, 180f)),
            prefs,
        ),
        AxisConfig(
            "Pitch", 60f, 90f,
            listOf(Pt(0f, 0f), Pt(15f, 20f), Pt(30f, 45f), Pt(45f, 70f), Pt(60f, 90f)),
            prefs,
        ),
        AxisConfig(
            "Roll", 45f, 45f,
            listOf(Pt(0f, 0f), Pt(10f, 10f), Pt(20f, 20f), Pt(30f, 30f), Pt(45f, 45f)),
            prefs,
        ),
    )

    fun save() {
        prefs.edit()
            .putFloat("minCutoff", minCutoff)
            .putFloat("beta", beta)
            .putBoolean("sendPosition", sendPosition)
            .putBoolean("manualEnabled", manualEnabled)
            .putString("manualIp", manualIp)
            .putInt("manualPort", manualPort)
            .apply()
    }

    companion object {
        private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")

        fun validIp(ip: String) = IPV4.matches(ip.trim())

        fun validPort(port: Int) = port in 1024..65535
    }
}
