package com.fuguang.lueying

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * 115 的 m115 加密：分享下载地址接口（proapi.115.com/app/share/downurl）的请求和返回都要过它。
 * 照 SheltonZhu/115driver 的 pkg/crypto/m115（public.go / rsa.go / xor.go）逐行移植，算法别改。
 *
 * 发：16 字节随机 key + 明文 → 明文段做两轮 XOR 加一次倒序 → 整段按 117 字节一块做 RSA（PKCS#1 v1.5 type 2 填充）→ Base64。
 * 收：Base64 → 每 128 字节一块用公钥做模幂、去掉填充 → 前 16 字节是服务器的 key，后面倒着把 XOR 解回来。
 */
internal object Pan115Crypt {
    private val N = BigInteger(
        "8686980c0f5a24c4b9d43020cd2c22703ff3f450756529058b1cf88f09b86021" +
            "36477198a6e2683149659bd122c33592fdb5ad47944ad1ea4d36c6b172aad633" +
            "8c3bb6ac6227502d010993ac967d1aef00f0c8e038de2e4d3bc2ec368af2e9f1" +
            "0a6f1eda4f7262f136420c07c331b871bf139f74f3010e3c4fe57df3afb71683", 16,
    )
    private val E = BigInteger("10001", 16)
    /** 128：N 是 1024 位。 */
    private val KEY_LEN = N.bitLength() / 8

    private val rnd = SecureRandom()

    // 预先算好的 XOR 种子表（115 客户端里抠出来的）
    private val SEED = bytes(
        0xf0, 0xe5, 0x69, 0xae, 0xbf, 0xdc, 0xbf, 0x8a, 0x1a, 0x45, 0xe8, 0xbe, 0x7d, 0xa6, 0x73, 0xb8,
        0xde, 0x8f, 0xe7, 0xc4, 0x45, 0xda, 0x86, 0xc4, 0x9b, 0x64, 0x8b, 0x14, 0x6a, 0xb4, 0xf1, 0xaa,
        0x38, 0x01, 0x35, 0x9e, 0x26, 0x69, 0x2c, 0x86, 0x00, 0x6b, 0x4f, 0xa5, 0x36, 0x34, 0x62, 0xa6,
        0x2a, 0x96, 0x68, 0x18, 0xf2, 0x4a, 0xfd, 0xbd, 0x6b, 0x97, 0x8f, 0x4d, 0x8f, 0x89, 0x13, 0xb7,
        0x6c, 0x8e, 0x93, 0xed, 0x0e, 0x0d, 0x48, 0x3e, 0xd7, 0x2f, 0x88, 0xd8, 0xfe, 0xfe, 0x7e, 0x86,
        0x50, 0x95, 0x4f, 0xd1, 0xeb, 0x83, 0x26, 0x34, 0xdb, 0x66, 0x7b, 0x9c, 0x7e, 0x9d, 0x7a, 0x81,
        0x32, 0xea, 0xb6, 0x33, 0xde, 0x3a, 0xa9, 0x59, 0x34, 0x66, 0x3b, 0xaa, 0xba, 0x81, 0x60, 0x48,
        0xb9, 0xd5, 0x81, 0x9c, 0xf8, 0x6c, 0x84, 0x77, 0xff, 0x54, 0x78, 0x26, 0x5f, 0xbe, 0xe8, 0x1e,
        0x36, 0x9f, 0x34, 0x80, 0x5c, 0x45, 0x2c, 0x9b, 0x76, 0xd5, 0x1b, 0x8f, 0xcc, 0xc3, 0xb8, 0xf5,
    )
    private val CLIENT_KEY = bytes(0x78, 0x06, 0xad, 0x4c, 0x33, 0x86, 0x5d, 0x18, 0x4c, 0x01, 0x3f, 0x46)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** 每次请求新生成一个 16 字节 key，解返回时要用同一个。 */
    fun newKey(): ByteArray = ByteArray(16).also { rnd.nextBytes(it) }

    /** 加密请求体（一般是 JSON 文本的字节），返回 Base64，作为表单里的 data=。 */
    fun encode(input: ByteArray, key: ByteArray): String {
        val buf = ByteArray(16 + input.size)
        key.copyInto(buf, 0, 0, 16)
        input.copyInto(buf, 16)
        val body = buf.copyOfRange(16, buf.size)
        xor(body, deriveKey(key, 4))
        body.reverse()
        xor(body, CLIENT_KEY)
        body.copyInto(buf, 16)
        return Base64.getEncoder().encodeToString(rsaEncrypt(buf))
    }

    /** 解返回里的 data 字段（Base64）。key 是发请求时用的那个。 */
    fun decode(input: String, key: ByteArray): ByteArray {
        val data = rsaDecrypt(Base64.getMimeDecoder().decode(input))
        require(data.size >= 16) { "m115 解出来太短" }
        val out = data.copyOfRange(16, data.size)
        xor(out, deriveKey(data.copyOfRange(0, 16), 12))
        out.reverse()
        xor(out, deriveKey(key, 4))
        return out
    }

    // ———— XOR ————

    internal fun deriveKey(seed: ByteArray, size: Int): ByteArray {
        val k = ByteArray(size)
        for (i in 0 until size) {
            // Go 里是 byte 相加自然溢出，这里按 0..255 加完再截低 8 位
            val v = ((seed[i].toInt() and 0xff) + (SEED[size * i].toInt() and 0xff)) and 0xff
            k[i] = (v xor (SEED[size * (size - i - 1)].toInt() and 0xff)).toByte()
        }
        return k
    }

    /** 先把多出来的 len%4 个字节用 key 头部异或，剩下的从 key 开头重新数。 */
    internal fun xor(data: ByteArray, key: ByteArray) {
        val mod = data.size % 4
        for (i in 0 until mod) data[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        for (i in mod until data.size) data[i] = (data[i].toInt() xor key[(i - mod) % key.size].toInt()).toByte()
    }

    // ———— RSA（公钥，手工填充；javax.crypto 的 RSA 解不了「公钥解密」，所以直接用 BigInteger 模幂） ————

    internal fun rsaEncrypt(input: ByteArray, pad: (ByteArray) -> Unit = { rnd.nextBytes(it) }): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var off = 0
        while (off < input.size) {
            val n = minOf(KEY_LEN - 11, input.size - off)
            out.write(rsaEncryptBlock(input.copyOfRange(off, off + n), pad))
            off += n
        }
        return out.toByteArray()
    }

    private fun rsaEncryptBlock(input: ByteArray, pad: (ByteArray) -> Unit): ByteArray {
        val padSize = KEY_LEN - input.size - 3
        val padData = ByteArray(padSize).also(pad)
        val buf = ByteArray(KEY_LEN)
        buf[0] = 0; buf[1] = 2
        // 填充字节不能是 0：b % 0xff + 1 落在 1..255
        for (i in 0 until padSize) buf[2 + i] = ((padData[i].toInt() and 0xff) % 0xff + 1).toByte()
        buf[padSize + 2] = 0
        input.copyInto(buf, padSize + 3)
        return fixed(BigInteger(1, buf).modPow(E, N))
    }

    private fun rsaDecrypt(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var off = 0
        while (off < input.size) {
            val n = minOf(KEY_LEN, input.size - off)
            val ret = unsigned(BigInteger(1, input.copyOfRange(off, off + n)).modPow(E, N))
            // 去填充：跳过开头，找到第一个 0（不算第 0 位），后面就是明文
            for (i in 1 until ret.size) if (ret[i].toInt() == 0) { out.write(ret, i + 1, ret.size - i - 1); break }
            off += n
        }
        return out.toByteArray()
    }

    /** BigInteger 去掉符号位的 0，等于 Go 的 big.Int.Bytes()。 */
    private fun unsigned(b: BigInteger): ByteArray {
        val a = b.toByteArray()
        var s = 0
        while (s < a.size - 1 && a[s].toInt() == 0) s++
        return if (s == 0) a else a.copyOfRange(s, a.size)
    }

    /** 密文补齐到 128 字节（前面补 0）。 */
    private fun fixed(b: BigInteger): ByteArray {
        val a = unsigned(b)
        if (a.size >= KEY_LEN) return a
        return ByteArray(KEY_LEN - a.size) + a
    }
}
