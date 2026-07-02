package com.bunting.evaluation

import com.bunting.model.BuntingConfiguration
import com.bunting.model.BuntingEnvironment
import java.util.UUID

/** Encoder for Bunting config fingerprints. Byte-for-byte compatible with the
 *  admin reference codec in `bunting-admin/src/lib/fingerprint.ts`. */
object ConfigFingerprint {
    const val FORMAT: Int = 1

    /** Bits needed to index [count] paths: ceil(log2(count)); 0 for a single path. */
    fun bitWidth(count: Int): Int {
        if (count <= 1) return 0
        var bits = 0
        var n = count - 1
        while (n > 0) { bits += 1; n = n shr 1 }
        return bits
    }

    /** CRC-8/SMBUS (poly 0x07, init 0x00) over the payload bytes (0–255 each). */
    fun crc8(bytes: List<Int>): Int {
        var crc = 0
        for (b in bytes) {
            crc = crc xor (b and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc and 0xFF
    }

    private class BitWriter {
        private val bits = ArrayList<Int>()
        fun write(value: Int, width: Int) {
            if (width <= 0) return
            var i = width - 1
            while (i >= 0) { bits.add((value shr i) and 1); i -= 1 }
        }
        fun toBytes(): List<Int> {
            val out = ArrayList<Int>()
            var i = 0
            while (i < bits.size) {
                var b = 0
                for (j in 0 until 8) {
                    val bit = if (i + j < bits.size) bits[i + j] else 0
                    b = (b shl 1) or bit
                }
                out.add(b and 0xFF)
                i += 8
            }
            return out
        }
    }

    fun encode(
        configVersion: String,
        environmentIndex: Int,
        flagWidthsSortedByKey: List<Pair<String, Int>>,
        selections: Map<String, Int>,
    ): String {
        val writer = BitWriter()
        writer.write(FORMAT, 4)
        writer.write(environmentIndex, 2)
        for ((key, width) in flagWidthsSortedByKey) {
            writer.write(selections[key] ?: 0, width)
        }
        val bytes = writer.toBytes().toMutableList()
        bytes.add(crc8(bytes))
        val hex = bytes.joinToString("") { String.format("%02X", it) }
        return "$configVersion.$hex"
    }

    fun compute(
        configuration: BuntingConfiguration,
        environment: BuntingEnvironment,
        context: EvaluationContext,
        localId: UUID,
        customAttributeResolver: CustomAttributeResolver,
    ): String {
        val evaluator = FlagEvaluator(configuration, environment, context, localId, customAttributeResolver)
        val widths = ArrayList<Pair<String, Int>>()
        val selections = HashMap<String, Int>()
        for (key in configuration.flags.keys.sorted()) {
            widths.add(key to bitWidth(evaluator.pathCount(key)))
            selections[key] = evaluator.resolvePathIndex(key)
        }
        return encode(configuration.configVersion, environment.fingerprintIndex, widths, selections)
    }
}
