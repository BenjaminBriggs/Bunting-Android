package com.bunting.evaluation

import java.security.MessageDigest
import java.util.UUID

/** Deterministic 1–100 bucketing, byte-for-byte identical to the Swift SDK. */
object Bucketing {
    fun bucket(salt: String, localId: UUID): Int {
        // Java UUID.toString() is lowercase; Swift's uuidString is uppercase.
        val input = "$salt:${localId.toString().uppercase()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (digest[i].toLong() and 0xFF)
        }
        // Swift treats the 8 bytes as UInt64; use unsigned modulo so the sign bit
        // does not skew the result.
        val unsigned = value.toULong()
        return (unsigned % 100UL).toInt() + 1
    }
}
