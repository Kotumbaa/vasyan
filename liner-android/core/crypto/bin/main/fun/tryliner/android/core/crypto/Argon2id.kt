package `fun`.tryliner.android.core.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

internal object Argon2id {
    private const val ARGON2_VERSION = 0x13
    private const val ARGON2_TYPE_ID = 2
    private const val BLOCK_SIZE_BYTES = 1024
    private const val BLOCK_SIZE_LONGS = 128
    private const val SYNC_POINTS = 4

    fun hash(
        password: ByteArray,
        salt: ByteArray,
        parallelism: Int,
        iterations: Int,
        memoryKiB: Int,
        hashLength: Int,
        secret: ByteArray = ByteArray(0),
        associatedData: ByteArray = ByteArray(0),
    ): ByteArray {
        require(parallelism >= 1) { "parallelism must be >= 1" }
        require(iterations >= 1) { "iterations must be >= 1" }
        require(memoryKiB >= 8 * parallelism) { "memoryKiB must be >= 8 * parallelism" }
        require(hashLength >= 4) { "hashLength must be >= 4" }

        val segmentLength = memoryKiB / (SYNC_POINTS * parallelism)
        val laneLength = segmentLength * SYNC_POINTS
        val lanes = Array(parallelism) { Array(laneLength) { LongArray(BLOCK_SIZE_LONGS) } }

        val h0 = initialHash(
            password = password,
            salt = salt,
            parallelism = parallelism,
            hashLength = hashLength,
            memoryKiB = memoryKiB,
            iterations = iterations,
            secret = secret,
            associatedData = associatedData,
        )

        for (lane in 0 until parallelism) {
            for (index in 0..1) {
                val input = ByteArray(72)
                System.arraycopy(h0, 0, input, 0, 64)
                writeLe32(input, 64, index)
                writeLe32(input, 68, lane)
                val blockBytes = blake2bLong(input, BLOCK_SIZE_BYTES)
                bytesToLongs(blockBytes, lanes[lane][index])
            }
        }

        val addressBlock = LongArray(BLOCK_SIZE_LONGS)
        val inputBlock = LongArray(BLOCK_SIZE_LONGS)
        val zeroBlock = LongArray(BLOCK_SIZE_LONGS)
        val tempBlock = LongArray(BLOCK_SIZE_LONGS)

        for (pass in 0 until iterations) {
            for (slice in 0 until SYNC_POINTS) {
                val dataIndependent = pass == 0 && slice < 2
                for (lane in 0 until parallelism) {
                    var addressCounter = 0L
                    if (dataIndependent) {
                        inputBlock.fill(0L)
                        inputBlock[0] = pass.toLong()
                        inputBlock[1] = lane.toLong()
                        inputBlock[2] = slice.toLong()
                        inputBlock[3] = (laneLength * parallelism).toLong()
                        inputBlock[4] = iterations.toLong()
                        inputBlock[5] = ARGON2_TYPE_ID.toLong()
                    }

                    val startIndex = if (pass == 0 && slice == 0) 2 else 0
                    for (offset in startIndex until segmentLength) {
                        val currIdx = slice * segmentLength + offset
                        val prevIdx = if (currIdx == 0) laneLength - 1 else currIdx - 1

                        val pseudoRand: Long = if (dataIndependent) {
                            val addrMod = offset % BLOCK_SIZE_LONGS
                            if (offset == startIndex || addrMod == 0) {
                                addressCounter += 1L
                                inputBlock[6] = addressCounter
                                compressG(zeroBlock, inputBlock, tempBlock)
                                compressG(zeroBlock, tempBlock, addressBlock)
                            }
                            addressBlock[addrMod]
                        } else {
                            lanes[lane][prevIdx][0]
                        }

                        val j1 = pseudoRand and 0xFFFFFFFFL
                        val j2 = (pseudoRand ushr 32) and 0xFFFFFFFFL
                        val refLane = if (pass == 0 && slice == 0) {
                            lane
                        } else {
                            (j2 % parallelism.toLong()).toInt()
                        }

                        val sameLane = refLane == lane
                        val refAreaSize = if (pass == 0) {
                            if (slice == 0) {
                                offset - 1
                            } else if (sameLane) {
                                slice * segmentLength + offset - 1
                            } else {
                                slice * segmentLength - (if (offset == 0) 1 else 0)
                            }
                        } else {
                            if (sameLane) {
                                laneLength - segmentLength + offset - 1
                            } else {
                                laneLength - segmentLength - (if (offset == 0) 1 else 0)
                            }
                        }

                        val relPos = indexAlpha(j1, refAreaSize)
                        val startPos = if (pass != 0 && slice != SYNC_POINTS - 1) {
                            (slice + 1) * segmentLength
                        } else {
                            0
                        }
                        val refIdx = (startPos + relPos) % laneLength

                        compressG(
                            lanes[lane][prevIdx],
                            lanes[refLane][refIdx],
                            tempBlock,
                        )
                        if (pass == 0) {
                            System.arraycopy(tempBlock, 0, lanes[lane][currIdx], 0, BLOCK_SIZE_LONGS)
                        } else {
                            val dst = lanes[lane][currIdx]
                            for (k in 0 until BLOCK_SIZE_LONGS) {
                                dst[k] = dst[k] xor tempBlock[k]
                            }
                        }
                    }
                }
            }
        }

        val finalBlock = LongArray(BLOCK_SIZE_LONGS)
        System.arraycopy(lanes[0][laneLength - 1], 0, finalBlock, 0, BLOCK_SIZE_LONGS)
        for (lane in 1 until parallelism) {
            val tail = lanes[lane][laneLength - 1]
            for (k in 0 until BLOCK_SIZE_LONGS) {
                finalBlock[k] = finalBlock[k] xor tail[k]
            }
        }

        val finalBytes = longsToBytes(finalBlock)
        return blake2bLong(finalBytes, hashLength)
    }

    private fun indexAlpha(j1: Long, refAreaSize: Int): Int {
        var x = (j1 * j1) ushr 32
        var y = (refAreaSize.toLong() * x) ushr 32
        return (refAreaSize.toLong() - 1L - y).toInt()
    }

    private fun initialHash(
        password: ByteArray,
        salt: ByteArray,
        parallelism: Int,
        hashLength: Int,
        memoryKiB: Int,
        iterations: Int,
        secret: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val buf = ByteBuffer.allocate(40 + password.size + salt.size + secret.size + associatedData.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(parallelism)
        buf.putInt(hashLength)
        buf.putInt(memoryKiB)
        buf.putInt(iterations)
        buf.putInt(ARGON2_VERSION)
        buf.putInt(ARGON2_TYPE_ID)
        buf.putInt(password.size)
        buf.put(password)
        buf.putInt(salt.size)
        buf.put(salt)
        buf.putInt(secret.size)
        buf.put(secret)
        buf.putInt(associatedData.size)
        buf.put(associatedData)
        return Blake2b.digest(buf.array(), 64)
    }

    private fun blake2bLong(input: ByteArray, outLen: Int): ByteArray {
        val prefixed = ByteArray(4 + input.size)
        writeLe32(prefixed, 0, outLen)
        System.arraycopy(input, 0, prefixed, 4, input.size)

        if (outLen <= 64) {
            return Blake2b.digest(prefixed, outLen)
        }

        val out = ByteArray(outLen)
        var v = Blake2b.digest(prefixed, 64)
        System.arraycopy(v, 0, out, 0, 32)
        var pos = 32
        val r = (outLen + 31) / 32 - 2
        for (i in 2..r) {
            v = Blake2b.digest(v, 64)
            System.arraycopy(v, 0, out, pos, 32)
            pos += 32
        }
        val rem = outLen - pos
        val last = Blake2b.digest(v, rem)
        System.arraycopy(last, 0, out, pos, rem)
        return out
    }

    private fun compressG(x: LongArray, y: LongArray, out: LongArray) {
        val r = LongArray(BLOCK_SIZE_LONGS)
        for (i in 0 until BLOCK_SIZE_LONGS) {
            r[i] = x[i] xor y[i]
        }
        val z = r.copyOf()

        // Column-wise 8x16
        for (i in 0 until 8) {
            val base = 16 * i
            pPermute(
                z,
                base, base + 1, base + 2, base + 3,
                base + 4, base + 5, base + 6, base + 7,
                base + 8, base + 9, base + 10, base + 11,
                base + 12, base + 13, base + 14, base + 15,
            )
        }
        // Row-wise
        for (i in 0 until 8) {
            val b = 2 * i
            pPermute(
                z,
                b, b + 1, b + 16, b + 17,
                b + 32, b + 33, b + 48, b + 49,
                b + 64, b + 65, b + 80, b + 81,
                b + 96, b + 97, b + 112, b + 113,
            )
        }

        for (i in 0 until BLOCK_SIZE_LONGS) {
            out[i] = r[i] xor z[i]
        }
    }

    private fun pPermute(
        v: LongArray,
        i0: Int, i1: Int, i2: Int, i3: Int,
        i4: Int, i5: Int, i6: Int, i7: Int,
        i8: Int, i9: Int, i10: Int, i11: Int,
        i12: Int, i13: Int, i14: Int, i15: Int,
    ) {
        gb(v, i0, i4, i8, i12)
        gb(v, i1, i5, i9, i13)
        gb(v, i2, i6, i10, i14)
        gb(v, i3, i7, i11, i15)
        gb(v, i0, i5, i10, i15)
        gb(v, i1, i6, i11, i12)
        gb(v, i2, i7, i8, i13)
        gb(v, i3, i4, i9, i14)
    }

    private fun gb(v: LongArray, a: Int, b: Int, c: Int, d: Int) {
        v[a] = fBlaMka(v[a], v[b])
        v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 32)
        v[c] = fBlaMka(v[c], v[d])
        v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 24)
        v[a] = fBlaMka(v[a], v[b])
        v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 16)
        v[c] = fBlaMka(v[c], v[d])
        v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 63)
    }

    private fun fBlaMka(x: Long, y: Long): Long {
        val m = 0xFFFFFFFFL
        val xy = (x and m) * (y and m)
        return x + y + 2L * xy
    }

    private fun writeLe32(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun bytesToLongs(src: ByteArray, dst: LongArray) {
        val buf = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        for (i in dst.indices) {
            dst[i] = buf.getLong()
        }
    }

    private fun longsToBytes(src: LongArray): ByteArray {
        val buf = ByteBuffer.allocate(src.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (v in src) {
            buf.putLong(v)
        }
        return buf.array()
    }
}

private object Blake2b {
    private val IV = longArrayOf(
        0x6a09e667f3bcc908L, -0x4498517a7b3558c5L,
        0x3c6ef372fe94f82bL, -0x5ab00ac5a0e2c90fL,
        0x510e527fade682d1L, -0x64fa9773d4c193e1L,
        0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
    )

    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
    )

    fun digest(input: ByteArray, outLen: Int): ByteArray {
        val h = IV.copyOf()
        h[0] = h[0] xor (0x01010000L or outLen.toLong())

        val block = ByteArray(128)
        var pos = 0
        var t = 0L
        while (input.size - pos > 128) {
            System.arraycopy(input, pos, block, 0, 128)
            pos += 128
            t += 128L
            compress(h, block, t, last = false)
        }

        val rem = input.size - pos
        block.fill(0)
        if (rem > 0) {
            System.arraycopy(input, pos, block, 0, rem)
        }
        t += rem.toLong()
        compress(h, block, t, last = true)

        val out = ByteArray(outLen)
        val buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        for (v in h) buf.putLong(v)
        System.arraycopy(buf.array(), 0, out, 0, min(outLen, 64))
        return out
    }

    private fun compress(h: LongArray, block: ByteArray, t: Long, last: Boolean) {
        val m = LongArray(16)
        val buf = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 16) m[i] = buf.getLong()

        val v = LongArray(16)
        System.arraycopy(h, 0, v, 0, 8)
        System.arraycopy(IV, 0, v, 8, 8)
        v[12] = v[12] xor t
        if (last) v[14] = v[14] xor -1L

        for (r in 0 until 12) {
            val s = SIGMA[r]
            g(v, m, 0, 4, 8, 12, s[0], s[1])
            g(v, m, 1, 5, 9, 13, s[2], s[3])
            g(v, m, 2, 6, 10, 14, s[4], s[5])
            g(v, m, 3, 7, 11, 15, s[6], s[7])
            g(v, m, 0, 5, 10, 15, s[8], s[9])
            g(v, m, 1, 6, 11, 12, s[10], s[11])
            g(v, m, 2, 7, 8, 13, s[12], s[13])
            g(v, m, 3, 4, 9, 14, s[14], s[15])
        }

        for (i in 0 until 8) {
            h[i] = h[i] xor v[i] xor v[i + 8]
        }
    }

    private fun g(v: LongArray, m: LongArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
        v[a] = v[a] + v[b] + m[x]
        v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 32)
        v[c] = v[c] + v[d]
        v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 24)
        v[a] = v[a] + v[b] + m[y]
        v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 63)
    }
}
