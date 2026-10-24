package com.tengwear.tengfilemanager.core.lan

import android.content.Context
import com.tengwear.tengfilemanager.core.fs.StorageGateway
import com.tengwear.tengfilemanager.core.model.FileEntry
import com.tengwear.tengfilemanager.core.util.formatBytes
import com.tengwear.tengfilemanager.core.util.uniqueName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网点对点传输（服务端 + 客户端）。
 *
 * 关键取舍：
 *  · **一次只服务一个对端**：手表 RAM 与带宽都有限，并发会话只会让两边都变慢；
 *  · **断点续传按内容 id 找回 .part**：中断后重新发起同一文件，自动从已收长度继续；
 *  · **落盘后整体校验**：续传的字节可能来自上一次会话，只有对最终文件整体算 SHA-256
 *    才能证明"两端内容一致"，代价是一次额外读盘；
 *  · **目录不直传**：手表上递归发送没有可靠的中断恢复语义，引导用户先压缩成单文件。
 */
class LanTransfer(
    private val context: Context,
    private val fs: StorageGateway,
    @Volatile private var deviceId: String,
    @Volatile private var deviceName: String,
) {

    /** 偏好是异步加载的，初始化时先给空值，拿到后再注入。 */
    fun setIdentity(id: String, name: String) {
        if (id.isNotBlank()) deviceId = id
        if (name.isNotBlank()) deviceName = name
    }

    enum class Direction { SEND, RECEIVE }

    enum class Phase { IDLE, LISTENING, CONNECTING, WAITING_ACCEPT, TRANSFERRING, VERIFYING, DONE, FAILED }

    data class ItemDesc(val id: String, val name: String, val size: Long, val isDirectory: Boolean)

    data class IncomingRequest(
        val peerName: String,
        val code: String,
        val items: List<ItemDesc>,
        val totalBytes: Long,
    )

    data class State(
        val phase: Phase = Phase.IDLE,
        val direction: Direction? = null,
        val peerName: String = "",
        val code: String = "",
        val currentFile: String = "",
        val bytesDone: Long = 0L,
        val bytesTotal: Long = 0L,
        val speedBps: Long = 0L,
        val message: String = "",
        val pending: IncomingRequest? = null,
        val listeningPort: Int = 0,
    ) {
        val fraction: Float
            get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var pendingDecision: CompletableDeferred<Boolean>? = null
    private val cancelled = AtomicBoolean(false)

    private val inboxDir: File get() = File(context.filesDir, "LanInbox").apply { mkdirs() }
    private val partsDir: File get() = File(inboxDir, ".parts").apply { mkdirs() }

    // ================================================================ 服务端

    fun startServer(): Int {
        if (serverJob?.isActive == true) return _state.value.listeningPort
        val socket = runCatching {
            ServerSocket(0).apply { reuseAddress = true }
        }.getOrElse {
            _state.update { s -> s.copy(phase = Phase.FAILED, message = "无法监听端口：${it.message}") }
            return 0
        }
        serverSocket = socket
        _state.update { it.copy(phase = Phase.LISTENING, listeningPort = socket.localPort) }
        serverJob = scope.launch {
            while (isActive) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                activeSocket = client
                runCatching { handleIncoming(client) }
                    .onFailure { t ->
                        _state.update { s ->
                            s.copy(
                                phase = Phase.FAILED,
                                message = t.message?.take(60) ?: "接收失败",
                                pending = null,
                            )
                        }
                    }
                runCatching { client.close() }
                activeSocket = null
                TransferService.stop(context)
                _state.update { s ->
                    if (s.phase == Phase.FAILED) s else s.copy(
                        phase = Phase.LISTENING, direction = null, pending = null,
                        bytesDone = 0, bytesTotal = 0, currentFile = "",
                    )
                }
            }
        }
        return socket.localPort
    }

    fun stopServer() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        _state.update { it.copy(phase = Phase.IDLE, listeningPort = 0) }
    }

    private suspend fun handleIncoming(socket: Socket) {
        socket.soTimeout = 30_000
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())

        val hello = input.readControl()
        if (hello.optString("t") != LanProtocol.T_HELLO) {
            output.writeControl(LanProtocol.bye("协议不匹配"))
            return
        }
        val peerName = hello.optString("name", "未知设备")

        val offer = input.readControl()
        if (offer.optString("t") != LanProtocol.T_OFFER) {
            output.writeControl(LanProtocol.bye("未收到文件清单"))
            return
        }
        val itemsJson = offer.optJSONArray("items") ?: org.json.JSONArray()
        val items = (0 until itemsJson.length()).mapNotNull { i ->
            val o = itemsJson.optJSONObject(i) ?: return@mapNotNull null
            ItemDesc(
                id = o.optString("id"),
                name = o.optString("name"),
                size = o.optLong("size"),
                isDirectory = o.optBoolean("dir"),
            )
        }.filterNot { it.isDirectory }
        if (items.isEmpty()) {
            output.writeControl(LanProtocol.bye("没有可接收的文件"))
            return
        }

        val code = LanProtocol.code()
        output.writeControl(LanProtocol.hello(deviceId, deviceName, code))

        // 交给 UI 决策；60 秒无人操作自动拒绝，避免端口被长期占用
        val decision = CompletableDeferred<Boolean>()
        pendingDecision = decision
        _state.update {
            it.copy(
                phase = Phase.WAITING_ACCEPT,
                direction = Direction.RECEIVE,
                peerName = peerName,
                code = code,
                pending = IncomingRequest(peerName, code, items, items.sumOf { i -> i.size }),
            )
        }
        TransferService.start(context, "等待接收：$peerName")

        val accepted = withTimeoutOrNull(60_000) { decision.await() } ?: false
        pendingDecision = null
        if (!accepted) {
            output.writeControl(LanProtocol.ready(false, "对方已拒绝"))
            return
        }
        output.writeControl(LanProtocol.ready(true))
        output.writeControl(LanProtocol.accept(items.map { it.id }))

        _state.update { it.copy(phase = Phase.TRANSFERRING, pending = null, bytesTotal = items.sumOf { i -> i.size }) }
        socket.soTimeout = 0

        var received = 0
        var bytesDone = 0L
        while (true) {
            val control = runCatching { input.readControl() }.getOrNull() ?: break
            when (control.optString("t")) {
                LanProtocol.T_BEGIN -> {
                    val id = control.optString("id")
                    val name = control.optString("name")
                    val size = control.optLong("size")
                    _state.update { it.copy(currentFile = name) }

                    val part = File(partsDir, "$id.part")
                    val offset = if (part.exists()) part.length().coerceAtMost(size) else 0L
                    output.writeControl(LanProtocol.resume(offset))

                    val digest = LanProtocol.sha256()
                    if (offset > 0) {
                        // 续传：把已有前缀重新喂进摘要，保证最终校验覆盖整文件
                        part.inputStream().use { stream ->
                            val buf = ByteArray(LanProtocol.CHUNK_SIZE)
                            while (true) {
                                val n = stream.read(buf)
                                if (n <= 0) break
                                digest.update(buf, 0, n)
                            }
                        }
                    }

                    var written = offset
                    FileOutputStreamAppender(part, offset).use { out ->
                        while (true) {
                            val header = input.readControl()
                            if (header.optString("t") == LanProtocol.T_END) {
                                val claimed = header.optString("sha256")
                                out.flush()
                                _state.update { it.copy(phase = Phase.VERIFYING) }
                                val actual = LanProtocol.hex(digest.digest())
                                if (actual.equals(claimed, ignoreCase = true) && written == size) {
                                    val taken = inboxDir.list()?.toSet() ?: emptySet()
                                    val finalName = uniqueName(name) { it in taken }
                                    val target = File(inboxDir, finalName)
                                    if (part.renameTo(target)) {
                                        output.writeControl(LanProtocol.ok(id))
                                        received++
                                        bytesDone += size
                                    } else {
                                        output.writeControl(LanProtocol.bad(id, "无法写入接收目录"))
                                    }
                                } else {
                                    // 校验失败：删掉半成品，让用户重传而不是留下坏文件
                                    part.delete()
                                    output.writeControl(
                                        LanProtocol.bad(
                                            id,
                                            if (written != size) "长度不符（$written/$size）" else "校验和不匹配",
                                        )
                                    )
                                }
                                _state.update { it.copy(phase = Phase.TRANSFERRING) }
                                break
                            }
                            if (header.optString("t") != LanProtocol.T_CHUNK) continue
                            val len = header.optInt("len")
                            if (len <= 0 || len > LanProtocol.CHUNK_SIZE) break
                            val buffer = ByteArray(len)
                            val read = input.readChunk(buffer, len)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            bytesDone += read
                            _state.update { s ->
                                s.copy(bytesDone = bytesDone, speedBps = s.speedBps, currentFile = name)
                            }
                        }
                    }
                }

                LanProtocol.T_BYE -> break
            }
        }

        _state.update {
            it.copy(
                phase = Phase.DONE,
                message = "已接收 $received 个文件到「局域网接收」",
                currentFile = "",
                bytesDone = bytesDone,
            )
        }
    }

    // ================================================================ 客户端

    fun send(peer: LanDiscovery.Peer, entries: List<FileEntry>, parent: com.tengwear.tengfilemanager.core.model.Location) {
        val files = entries.filterNot { it.isDirectory }
        if (files.size != entries.size) {
            _state.update { it.copy(phase = Phase.FAILED, message = "暂不支持直接发送文件夹，请先压缩") }
            if (files.isEmpty()) return
        }
        cancelled.set(false)
        scope.launch {
            TransferService.start(context, "正在发送到 ${peer.name}")
            val socket = Socket()
            activeSocket = socket
            try {
                _state.update {
                    it.copy(
                        phase = Phase.CONNECTING, direction = Direction.SEND, peerName = peer.name,
                        bytesTotal = files.sumOf { f -> f.size }, bytesDone = 0, message = "",
                    )
                }
                socket.connect(InetSocketAddress(peer.host, peer.port), 8000)
                socket.soTimeout = 30_000
                val output = DataOutputStream(socket.getOutputStream().buffered())
                val input = DataInputStream(socket.getInputStream().buffered())

                output.writeControl(LanProtocol.hello(deviceId, deviceName))
                val serverHello = input.readControl()
                val code = serverHello.optString("code")
                _state.update { it.copy(code = code, phase = Phase.WAITING_ACCEPT) }

                val items = files.map { f ->
                    JSONObject().apply {
                        put("id", LanProtocol.fileId(f.name, f.size, f.lastModified))
                        put("name", f.name)
                        put("size", f.size)
                        put("dir", false)
                    }
                }
                output.writeControl(LanProtocol.offer(items, files.sumOf { it.size }))

                val ready = input.readControl()
                if (!ready.optBoolean("ok")) {
                    _state.update {
                        it.copy(phase = Phase.FAILED, message = ready.optString("reason", "对方拒绝了本次传输"))
                    }
                    return@launch
                }
                val acceptedIds = runCatching {
                    val arr = input.readControl().optJSONArray("ids")
                    (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }.toSet()
                }.getOrDefault(items.map { it.getString("id") }.toSet())

                socket.soTimeout = 0
                _state.update { it.copy(phase = Phase.TRANSFERRING) }

                var sent = 0
                var bytesDone = 0L
                for (file in files) {
                    if (cancelled.get()) break
                    val id = LanProtocol.fileId(file.name, file.size, file.lastModified)
                    if (id !in acceptedIds) continue

                    // 先整体摘要：续传时也能给出"整文件"校验和
                    _state.update { it.copy(phase = Phase.VERIFYING, currentFile = file.name) }
                    val digest = LanProtocol.sha256()
                    fs.openInput(file.uri).use { stream ->
                        val buf = ByteArray(LanProtocol.CHUNK_SIZE)
                        while (true) {
                            val n = stream.read(buf)
                            if (n <= 0) break
                            digest.update(buf, 0, n)
                        }
                    }
                    val fullDigest = LanProtocol.hex(digest.digest())

                    output.writeControl(LanProtocol.begin(id, file.name, file.size))
                    val resumeMsg = input.readControl()
                    val offset = resumeMsg.optLong("offset").coerceIn(0L, file.size)

                    _state.update { it.copy(phase = Phase.TRANSFERRING, currentFile = file.name) }
                    fs.openInput(file.uri).use { stream ->
                        skipFully(stream, offset)
                        var position = offset
                        val buffer = ByteArray(LanProtocol.CHUNK_SIZE)
                        while (true) {
                            if (cancelled.get()) break
                            val read = stream.read(buffer)
                            if (read <= 0) break
                            output.writeControl(LanProtocol.chunkHeader(read))
                            output.write(buffer, 0, read)
                            output.flush()
                            position += read
                            bytesDone += read
                            _state.update { s -> s.copy(bytesDone = bytesDone) }
                        }
                        output.writeControl(LanProtocol.end(id, fullDigest))
                        val result = input.readControl()
                        if (result.optString("t") == LanProtocol.T_OK) {
                            sent++
                        } else {
                            _state.update {
                                it.copy(
                                    phase = Phase.FAILED,
                                    message = "对方校验失败：${result.optString("reason", "未知原因")}",
                                )
                            }
                            return@launch
                        }
                    }
                }
                output.writeControl(LanProtocol.bye("done"))
                _state.update {
                    it.copy(
                        phase = Phase.DONE,
                        message = if (cancelled.get()) "已取消" else "已发送 $sent 个文件到 ${peer.name}",
                        currentFile = "",
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(phase = Phase.FAILED, message = t.message?.take(60) ?: "连接失败，请确认在同一 Wi-Fi")
                }
            } finally {
                runCatching { socket.close() }
                activeSocket = null
                TransferService.stop(context)
            }
        }
    }

    // ================================================================ 控制

    fun acceptIncoming() {
        pendingDecision?.complete(true)
        _state.update { it.copy(pending = null, phase = Phase.TRANSFERRING) }
    }

    fun rejectIncoming() {
        pendingDecision?.complete(false)
        _state.update { it.copy(pending = null, phase = Phase.LISTENING, direction = null) }
    }

    fun cancel() {
        cancelled.set(true)
        runCatching { activeSocket?.close() }
        _state.update { it.copy(phase = Phase.IDLE, message = "已取消", currentFile = "", pending = null) }
    }

    fun reset() {
        cancelled.set(false)
        _state.update { State(phase = if (serverSocket != null) Phase.LISTENING else Phase.IDLE) }
    }

    fun shutdown() {
        cancel()
        stopServer()
        // 这里不能 cancelAndJoin（挂起函数），进程退出前把 Job 取消即可
        scope.coroutineContext[Job]?.cancel()
    }

    fun inboxLocation(): com.tengwear.tengfilemanager.core.model.Location =
        com.tengwear.tengfilemanager.core.model.Location(
            android.net.Uri.fromFile(inboxDir), null, "局域网接收", isSandbox = true,
        )

    fun inboxSummary(): String {
        val files = inboxDir.listFiles()?.filter { it.isFile } ?: emptyList()
        return if (files.isEmpty()) "空" else "${files.size} 个文件 · ${formatBytes(files.sumOf { it.length() })}"
    }

    private fun skipFully(stream: java.io.InputStream, count: Long) {
        var remaining = count
        val sink = ByteArray(LanProtocol.CHUNK_SIZE)
        while (remaining > 0) {
            val skipped = stream.read(sink, 0, minOf(remaining, sink.size.toLong()).toInt())
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    /** 追加写入：断点续传必须从 offset 处继续写，而不是截断重来。 */
    private class FileOutputStreamAppender(file: File, offset: Long) : AutoCloseable {
        private val out = java.io.FileOutputStream(file, offset > 0)

        init {
            if (offset > 0 && file.length() > offset) {
                // 上一次会话多写了尾巴，截断到 offset
                java.io.RandomAccessFile(file, "rw").use { it.setLength(offset) }
            }
        }

        fun write(buffer: ByteArray, off: Int, len: Int) = out.write(buffer, off, len)
        fun flush() = out.flush()
        override fun close() = out.close()
    }
}
