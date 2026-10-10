package com.example.ets2headtracker

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Protocol {
    const val PC_PORT = 47991
    const val PHONE_PORT = 47992
    const val SIZE = 32
    const val T_PONG = 0
    const val T_POSE = 1
    const val T_PING = 2
    const val T_ACK = 3

    fun pack(type: Int, seq: Long, values: FloatArray? = null): ByteArray {
        val b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put('H'.code.toByte())
        b.put('T'.code.toByte())
        b.put(1.toByte())
        b.put(type.toByte())
        b.putInt(seq.toInt())
        for (i in 0 until 6) b.putFloat(values?.getOrNull(i) ?: 0f)
        return b.array()
    }

    fun type(data: ByteArray, length: Int): Int {
        if (length < SIZE) return -1
        if (data[0] != 'H'.code.toByte() || data[1] != 'T'.code.toByte() || data[2].toInt() != 1) return -1
        return data[3].toInt() and 0xFF
    }
}
