package com.tengwear.tengfilemanager.core.lan

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * 局域网传输协议 v1（`_tengfile._tcp`）。
 *
 * 帧格式：`[4 字节大端长度][UTF-8 JSON]`，数据块帧在 JSON 头之后紧跟 len 字节裸数据。
 * 选 JSON 而不是 protobuf：协议只有 10 条消息，JSON 可读、可抓包调试，
 * 解析开销在 64KB 分块下可忽略。
 *
 * 会话时序：
 * ```
 * client                        server
 *   ── hello ─────────────────▶
 *   ◀────────────── hello+code ──     （双方屏幕显示同一 4 位码，供肉眼比对）
 *   ◀─────────────── ready/bye ──     （server 用户点「接收」/「拒绝」）
 *   ── offer[items] ──────────▶
 *   ◀────────────── accept[ids] ──
 *   ── file_begin{id,name,size}▶
 *   ◀────────────── file_resume{offset}   （断点续传：从 offset 开始发）
 *   ── chunk × N ─────────────▶
 *   ── file_end{sha256} ──────▶
 *   ◀────────────── file_ok / file_bad
 * ```
 *
 * 安全模型（明确写在协议里，不做"看起来很安全"的设计）：
 *  · 明文 TCP，仅在用户确认的同一 Wi-Fi 局域网内直连，不经过任何服务器；
 *  · 配对靠 4 位码肉眼比对 + 接收方显式确认，防止"同名设备"钓鱼；
 *  · 不做传输加密：手表上 TLS 证书固定会显著增加包体与 CPU 开销，
 *    而局域网内传输本身已有物理隔离；如需更高安全等级，见文档「后续演进」。
 */
object LanProtocol {

    const val SERVICE_TYPE = "_tengfile._tcp."
    const val PROTO_VERSION = 1
    const val CHUNK_SIZE = 64 * 1024
    const val MAX_CONTROL_BYTES = 1 shl 20
    const val DEFAULT_PORT = 45871

    // 消息类型
    const val T_HELLO = "hello"
    const val T_READY = "ready"
    const val T_BYE = "bye"
    const val T_OFFER = "offer"
    const val T_ACCEPT = "accept"
    const val T_BEGIN = "file_begin"
    const val T_RESUME = "file_resume"
    const val T_CHUNK = "chunk"
    const val T_END = "file_end"
    const val T_OK = "file_ok"
    const val T_BAD = "file_bad"

    fun code(): String = String.format(Locale.US, "%04d", SecureRandom().nextInt(10_000))

    fun hello(deviceId: String, deviceName: String, code: String? = null): JSONObject = JSONObject().apply {
        put("t", T_HELLO)
        put("v", PROTO_VERSION)
        put("id", deviceId)
        put("name", deviceName)
        code?.let { put("code", it) }
    }

    fun ready(accepted: Boolean, reason: String? = null): JSONObject = JSONObject().apply {
        put("t", T_READY)
        put("ok", accepted)
        reason?.let { put("reason", it) }
    }

    fun bye(reason: String): JSONObject = JSONObject().put("t", T_BYE).put("reason", reason)

    fun offer(items: List<JSONObject>, totalBytes: Long): JSONObject = JSONObject().apply {
        put("t", T_OFFER)
        put("total", totalBytes)
        put("items", org.json.JSONArray(items))
    }

    fun accept(ids: List<String>): JSONObject = JSONObject().apply {
        put("t", T_ACCEPT)
        put("ids", org.json.JSONArray(ids))
    }

    fun begin(id: String, name: String, size: Long): JSONObject = JSONObject().apply {
        put("t", T_BEGIN)
        put("id", id)
        put("name", name)
        put("size", size)
    }

    fun resume(offset: Long): JSONObject = JSONObject().put("t", T_RESUME).put("offset", offset)

    fun chunkHeader(len: Int): JSONObject = JSONObject().put("t", T_CHUNK).put("len", len)

    fun end(id: String, sha256: String): JSONObject = JSONObject().apply {
        put("t", T_END)
        put("id", id)
        put("sha256", sha256)
    }

    fun ok(id: String): JSONObject = JSONObject().put("t", T_OK).put("id", id)

    fun bad(id: String, reason: String): JSONObject = JSONObject().apply {
        put("t", T_BAD)
        put("id", id)
        put("reason", reason)
    }

    /** 内容标识：name+size+mtime 派生，跨会话稳定，断点续传据此找回 .part 文件。 */
    fun fileId(name: String, size: Long, mtime: Long): String {
        val raw = "$name|$size|$mtime"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

// ---------------------------------------------------------------- 帧读写

fun DataOutputStream.writeControl(json: JSONObject) {
    val bytes = json.toString().toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
    flush()
}

fun DataInputStream.readControl(): JSONObject {
    val length = readInt()
    if (length <= 0 || length > LanProtocol.MAX_CONTROL_BYTES) {
        throw IllegalStateException("协议帧长度非法：$length")
    }
    val bytes = ByteArray(length)
    readFully(bytes)
    return JSONObject(String(bytes, Charsets.UTF_8))
}

/** 读取数据块，返回实际读取字节数；EOF 时返回 -1。 */
fun DataInputStream.readChunk(buffer: ByteArray, len: Int): Int {
    var read = 0
    while (read < len) {
        val n = read(buffer, read, len - read)
        if (n < 0) {
            if (read == 0) return -1
            throw EOFException("数据块被截断")
        }
        read += n
    }
    return read
}
