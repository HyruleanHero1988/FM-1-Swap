package com.fmswitch.fm1kit

/**
 * Yamaha DX7 packed voice (128-byte VMEM) <-> 155-byte edit buffer (VCED).
 * Layout is the published DX7 MIDI data format.
 */
object Dx7Voice {
    const val PACKED_LENGTH = 128
    const val EDIT_LENGTH = 155

    fun unpack(packed: ByteArray): ByteArray {
        require(packed.size == PACKED_LENGTH) { "A packed DX7 voice is $PACKED_LENGTH bytes." }
        val edit = ByteArray(EDIT_LENGTH)
        for (op in 0 until 6) {
            val src = op * 17
            val dst = op * 21
            packed.copyInto(edit, dst, src, src + 11)
            val curves = packed[src + 11].toUInt8()
            edit[dst + 11] = (curves and 3).toUByte()
            edit[dst + 12] = ((curves shr 2) and 3).toUByte()
            val rateDetune = packed[src + 12].toUInt8()
            edit[dst + 13] = (rateDetune and 7).toUByte()
            edit[dst + 20] = ((rateDetune shr 3) and 0x0F).toUByte()
            val ampVel = packed[src + 13].toUInt8()
            edit[dst + 14] = (ampVel and 3).toUByte()
            edit[dst + 15] = ((ampVel shr 2) and 7).toUByte()
            edit[dst + 16] = packed[src + 14]
            val modeCoarse = packed[src + 15].toUInt8()
            edit[dst + 17] = (modeCoarse and 1).toUByte()
            edit[dst + 18] = ((modeCoarse shr 1) and 0x1F).toUByte()
            edit[dst + 19] = packed[src + 16]
        }
        packed.copyInto(edit, 126, 102, 110)
        edit[134] = packed[110]
        val feedbackSync = packed[111].toUInt8()
        edit[135] = (feedbackSync and 7).toUByte()
        edit[136] = ((feedbackSync shr 3) and 1).toUByte()
        packed.copyInto(edit, 137, 112, 116)
        val lfo = packed[116].toUInt8()
        edit[141] = (lfo and 1).toUByte()
        edit[142] = ((lfo shr 1) and 7).toUByte()
        edit[143] = ((lfo shr 4) and 7).toUByte()
        edit[144] = packed[117]
        packed.copyInto(edit, 145, 118, 128)
        return edit
    }

    fun pack(edit: ByteArray): ByteArray {
        require(edit.size == EDIT_LENGTH) { "A DX7 edit buffer is $EDIT_LENGTH bytes." }
        val packed = ByteArray(PACKED_LENGTH)
        for (op in 0 until 6) {
            val dst = op * 21
            val src = op * 17
            edit.copyInto(packed, src, dst, dst + 11)
            packed[src + 11] = ((edit[dst + 11].toUInt8() and 3) or ((edit[dst + 12].toUInt8() and 3) shl 2)).toUByte()
            packed[src + 12] = ((edit[dst + 13].toUInt8() and 7) or ((edit[dst + 20].toUInt8() and 0x0F) shl 3)).toUByte()
            packed[src + 13] = ((edit[dst + 14].toUInt8() and 3) or ((edit[dst + 15].toUInt8() and 7) shl 2)).toUByte()
            packed[src + 14] = edit[dst + 16]
            packed[src + 15] = ((edit[dst + 17].toUInt8() and 1) or ((edit[dst + 18].toUInt8() and 0x1F) shl 1)).toUByte()
            packed[src + 16] = edit[dst + 19]
        }
        edit.copyInto(packed, 102, 126, 134)
        packed[110] = edit[134]
        packed[111] = ((edit[135].toUInt8() and 7) or ((edit[136].toUInt8() and 1) shl 3)).toUByte()
        edit.copyInto(packed, 112, 137, 141)
        packed[116] = (
            (edit[141].toUInt8() and 1) or
                ((edit[142].toUInt8() and 7) shl 1) or
                ((edit[143].toUInt8() and 7) shl 4)
            ).toUByte()
        packed[117] = edit[144]
        edit.copyInto(packed, 118, 145, 155)
        return packed
    }

    fun nameOf(packed: ByteArray): String {
        require(packed.size == PACKED_LENGTH)
        return packed.copyOfRange(118, 128).map { b ->
            val c = b.toUInt8()
            if (c in 0x20..0x7E) c.toChar() else '?'
        }.joinToString("").trimEnd()
    }
}
