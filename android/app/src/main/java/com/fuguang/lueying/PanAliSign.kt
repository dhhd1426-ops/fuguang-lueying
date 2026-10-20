package com.fuguang.lueying

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 阿里网页版接口的设备签名（x-signature）。
 * 曲线是 secp256k1，安卓自带的加密库（Conscrypt）和新版 JDK 都不认这条曲线，这里用 BigInteger 直接算。
 * 只在建会话时签一次，慢一点没关系，所以用最直白的仿射坐标。
 *
 * 写法照 OpenList drivers/aliyundrive（dustinxie/ecc 的 SignBytes(RecID|LowerS)）：
 * 签名 = r(32 字节) + s(32 字节，取小的那个) + v(1 字节恢复号)，十六进制；公钥 = X(32) + Y(32)，十六进制，不带 04。
 */
internal object PanAliSign {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G = Pt(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
    )
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val rnd = SecureRandom()

    private class Pt(val x: BigInteger, val y: BigInteger)

    /** 两点相加；null 是无穷远点。 */
    private fun add(a: Pt?, b: Pt?): Pt? {
        if (a == null) return b
        if (b == null) return a
        val l = if (a.x == b.x) {
            if (a.y.add(b.y).mod(P).signum() == 0) return null
            THREE.multiply(a.x).multiply(a.x).multiply(TWO.multiply(a.y).modInverse(P)).mod(P)
        } else {
            b.y.subtract(a.y).multiply(b.x.subtract(a.x).modInverse(P)).mod(P)
        }
        val x = l.multiply(l).subtract(a.x).subtract(b.x).mod(P)
        val y = l.multiply(a.x.subtract(x)).subtract(a.y).mod(P)
        return Pt(x, y)
    }

    /** k·p，从高位往低位，倍加。 */
    private fun mul(k: BigInteger, p: Pt): Pt? {
        var r: Pt? = null
        for (i in k.bitLength() - 1 downTo 0) {
            r = add(r, r)
            if (k.testBit(i)) r = add(r, p)
        }
        return r
    }

    private fun hex(n: BigInteger, bytes: Int): String = n.toString(16).padStart(bytes * 2, '0')

    fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

    fun sha256Hex(s: String): String = sha256(s).joinToString("") { "%02x".format(it) }

    /** 由一段十六进制（OpenList 用 sha256(user_id)）得到私钥，落在 [1, N-1]。 */
    fun key(hex: String): BigInteger = BigInteger(hex, 16).mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE)

    /** 公钥：X + Y 各 32 字节的十六进制（128 个字符）。 */
    fun pub(d: BigInteger): String {
        val q = mul(d, G) ?: error("坏私钥")
        return hex(q.x, 32) + hex(q.y, 32)
    }

    /** 对 32 字节摘要签名，回 r + s + v 的十六进制（130 个字符）。 */
    fun sign(d: BigInteger, hash: ByteArray): String {
        val z = BigInteger(1, hash)
        while (true) {
            val k = BigInteger(256, rnd)
            if (k.signum() == 0 || k >= N) continue
            val rp = mul(k, G) ?: continue
            val r = rp.x.mod(N)
            if (r.signum() == 0) continue
            var s = k.modInverse(N).multiply(z.add(r.multiply(d))).mod(N)
            if (s.signum() == 0) continue
            var v = (if (rp.y.testBit(0)) 1 else 0) + (if (rp.x >= N) 2 else 0)
            // 取小的 s（s ≤ N/2），恢复号跟着翻
            if (s > N.shiftRight(1)) { s = N.subtract(s); v = v xor 1 }
            return hex(r, 32) + hex(s, 32) + "%02x".format(v)
        }
    }
}
