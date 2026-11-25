package dev.minios.tgwsproxy.proxy

import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel as Queue
import kotlinx.coroutines.sync.Semaphore
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.EventListener
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Lazy, epoch-scoped HTTP/2 lanes. OkHttp supplies RFC 7540 multiplexing,
 * flow control, stream cancellation and GOAWAY/reconnect handling.
 * Native MTProto packets (not the obfuscated relay stream) are POSTed to /api.
 */
class CfH2Pool internal constructor(
    private val connectivity: ConnectivityTracker,
    private val hooks: Hooks,
) {
    constructor(connectivity: ConnectivityTracker) : this(connectivity, Hooks())

    internal class Hooks(
        val dispatcher: CoroutineDispatcher = Dispatchers.IO,
        val clock: () -> Long = System::nanoTime,
        val callFactory: ((Request) -> Call)? = null,
        val post: (suspend (ByteArray, PendingRequest) -> ByteArray)? = null,
        val afterCloseSnapshot: (() -> Unit)? = null,
    )

    internal class ReplayPacket(val body: ByteArray, val admittedAt: Long, val sequence: Long) {
        var attempts = 0
        var lastAttemptAt: Long? = null
        var retired = false
        var replyId: ByteArray? = null
    }

    internal class PendingRequest(
        val body: ByteArray,
        val packet: ReplayPacket?,
        val replay: Boolean,
    ) {
        @Volatile var sentAt: Long? = null
        @Volatile var receivingHeaders = false
        @Volatile var rotating = false
        @Volatile var call: Call? = null
        lateinit var job: Job
    }

    private enum class Capacity { CHANNEL_SLOTS, CHANNEL_BYTES, LANE_SLOTS, LANE_BYTES }
    private data class Waiting(val capacity: Capacity, val bytes: Int)
    private val lanes = ConcurrentHashMap<String, Lane>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val stateLock = Any()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val activeDomains = ConcurrentHashMap<Int, String>()

    fun reset() {
        val retired = synchronized(stateLock) {
            generation.incrementAndGet()
            lanes.values.toList().also {
                lanes.clear()
                retryAfter.clear()
                locks.clear()
                activeDomains.clear()
            }
        }
        retired.forEach(Lane::close)
    }

    internal fun open(
        dc: Int,
        domains: List<String>,
        epoch: Long,
        timeoutMs: Long = 8_000,
        attempt: TransportAttemptResources? = null,
        // The calling session, not the attempt's inner deadline: a genuine preflight
        // timeout should still cool the host down while the session is running.
        callerIsActive: () -> Boolean = { attempt?.isActive != false },
        tlsAvailable: (String) -> Boolean = { true },
    ): Channel? {
        if (dc !in MtProtoConstants.DC_IPS) return null
        val currentGeneration = generation.get()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

        fun isCurrent() = callerIsActive() && attempt?.isActive != false &&
            generation.get() == currentGeneration && connectivity.isCurrent(epoch)

        fun publish(requireInternet: Boolean = false, action: () -> Unit): Boolean {
            var published = false
            connectivity.recordIfCurrent(epoch, requireInternet) {
                synchronized(stateLock) {
                    if (generation.get() == currentGeneration && callerIsActive()) {
                        action()
                        published = true
                    }
                }
            }
            return published
        }

        for (domain in domains.sortedBy { it != activeDomains[dc] }) {
            if (!isCurrent()) return null
            if (System.nanoTime() >= deadline) break
            if (!tlsAvailable(domain)) continue
            val host = MtProtoConstants.cloudflareHost(dc, domain)
            if ((retryAfter[host] ?: 0) > System.currentTimeMillis()) continue
            val lock = synchronized(stateLock) {
                if (generation.get() != currentGeneration) return null
                locks.computeIfAbsent(host) { ReentrantLock() }
            }
            if (!lock.tryLock()) continue // Do not queue behind another client's blocking preflight.
            var created: Lane? = null
            var ready = false
            try {
                if (!isCurrent()) return null
                var lane = synchronized(stateLock) {
                    if (generation.get() != currentGeneration) return null
                    lanes[host]?.takeIf { it.epoch <= epoch }
                }
                if (lane == null || lane.epoch != epoch || lane.closed) {
                    lane?.close()
                    val newLane = Lane(host, epoch)
                    created = newLane
                    // Publish before preflight so reset cancels the setup call too.
                    if (!isCurrent() || !publish { lanes[host] = newLane }) return null
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    if (remaining <= 0) return null
                    newLane.preflight(remaining, attempt)
                    lane = newLane
                }
                if (!isCurrent() || !tlsAvailable(domain)) continue
                if (!publish {
                    CfProxyDomains.setActiveDomain(dc, domain)
                    activeDomains[dc] = domain
                }) return null
                ready = true
                return Channel(lane, dc)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (publish(requireInternet = true) {
                    retryAfter[host] = System.currentTimeMillis() + 30_000
                }) {
                    DiagnosticLogger.warning("h2_setup_failed", "dc" to dc, "error" to cfTransportErrorForLog(e))
                }
            } finally {
                try {
                    if (!ready) created?.let {
                        lanes.remove(host, it)
                        it.close()
                    }
                } finally {
                    lock.unlock()
                }
            }
        }
        return null
    }

    inner class Lane(val host: String, val epoch: Long) {
        @Volatile var closed = false
        val slots = Semaphore(64)
        val uploads = ByteBudget(32 * 1024 * 1024)
        val replies = ByteBudget(32 * 1024 * 1024)
        val channels = ConcurrentHashMap.newKeySet<Channel>()
        private val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        private val tls = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trust), SecureRandom())
        }
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val socketFactory = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun close() {
                    try { super.close() } finally {
                        connectivity.releaseSocket(this)
                        sockets.remove(this)
                    }
                }
            }.also {
                sockets.add(it)
                if (closed || !connectivity.trackSocket(epoch, it)) {
                    it.close()
                    throw IOException("H2 network changed")
                }
            }
            override fun createSocket(h: String, p: Int): Socket = createSocket().apply { connect(java.net.InetSocketAddress(h, p)) }
            override fun createSocket(h: InetAddress, p: Int): Socket = createSocket(h.hostAddress!!, p)
            override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket =
                createSocket().apply { bind(java.net.InetSocketAddress(l, lp)); connect(java.net.InetSocketAddress(h, p)) }
            override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket =
                createSocket(h.hostAddress!!, p, l, lp)
        }
        private val client = OkHttpClient.Builder()
            .socketFactory(socketFactory)
            .sslSocketFactory(tls.socketFactory, trust)
            .hostnameVerifier { _, _ -> true } // Same deliberate transport policy as RawWebSocket.
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(1, 2, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(65, TimeUnit.SECONDS)
            .writeTimeout(65, TimeUnit.SECONDS)
            .callTimeout(65, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false) // MTProto replay is explicitly bounded below.
            .eventListener(object : EventListener() {
                override fun requestBodyEnd(call: Call, byteCount: Long) {
                    call.request().tag(PendingRequest::class.java)?.sentAt = hooks.clock()
                }
                override fun responseHeadersEnd(call: Call, response: Response) {
                    call.request().tag(PendingRequest::class.java)?.receivingHeaders = true
                }
            })
            .build()

        private fun newCall(request: Request): Call = hooks.callFactory?.invoke(request) ?: client.newCall(request)

        internal fun preflight(timeoutMs: Long, attempt: TransportAttemptResources? = null) {
            val call = newCall(Request.Builder().url("https://$host/api").head().build())
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            val cancel = AutoCloseable(call::cancel)
            attempt?.register(cancel)
            try {
                if (closed || !connectivity.trackResource(epoch, cancel)) {
                    throw IOException("H2 network changed")
                }
                call.execute().use {
                    if (it.protocol != Protocol.HTTP_2 || !(it.isSuccessful || it.code == 405 || it.code == 501)) {
                        throw IOException("H2 preflight failed")
                    }
                }
            } finally {
                connectivity.releaseResource(cancel)
                attempt?.release(cancel)
            }
        }

        internal suspend fun post(body: ByteArray, owner: Channel, pending: PendingRequest): ByteArray = withContext(hooks.dispatcher) {
            if (closed || !connectivity.isCurrent(epoch)) throw IOException("H2 lane closed")
            hooks.post?.let { perform ->
                val reply = perform(body, pending)
                if (!replies.tryAcquire(reply.size)) throw IOException("H2 lane response buffer limit")
                return@withContext reply
            }
            val call = newCall(Request.Builder().url("https://$host/api")
                .header("Accept-Encoding", "identity")
                .tag(PendingRequest::class.java, pending)
                .post(body.toRequestBody("application/octet-stream".toMediaType())).build())
            pending.call = call
            owner.calls.add(call)
            // Register before checking closure: close() either sees this call in its
            // snapshot, or its published flag makes this late registration cancel itself.
            if (closed || owner.closed || pending.rotating || !currentCoroutineContext().isActive) call.cancel()
            var buffered = 0
            var delivered = false
            try {
                call.execute().use { response ->
                    pending.receivingHeaders = true
                    if (response.protocol != Protocol.HTTP_2) throw IOException("H2 downgrade")
                    if (response.code in setOf(403, 404, 429, 444)) throw H2TransportError(-response.code)
                    if (response.code != 200 || response.header("Content-Type").orEmpty().contains("text/html", true)) {
                        throw IOException("Invalid H2 response")
                    }
                    val responseBody = response.body ?: throw IOException("Missing H2 body")
                    if (responseBody.contentLength() > NativeHttpCodec.MAX_PACKET) throw IOException("Oversized H2 response")
                    val content = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    responseBody.byteStream().use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (content.size() + count > NativeHttpCodec.MAX_PACKET) throw IOException("Oversized H2 response")
                            if (!replies.tryAcquire(count)) throw IOException("H2 lane response buffer limit")
                            buffered += count
                            content.write(buffer, 0, count)
                        }
                    }
                    val reply = content.toByteArray()
                    if (responseBody.contentLength() >= 0 && responseBody.contentLength() != reply.size.toLong()) {
                        throw IOException("Incomplete H2 response")
                    }
                    if (reply.size % 4 != 0) throw IOException("Unaligned H2 response")
                    if (reply.size == 4 && NativeHttpCodec.intLE(reply) < 0) throw H2TransportError(NativeHttpCodec.intLE(reply))
                    delivered = true
                    reply
                }
            } finally {
                if (!delivered) replies.release(buffered)
                owner.calls.remove(call)
            }
        }

        fun close() {
            closed = true
            channels.toList().forEach(Channel::close)
            client.dispatcher.cancelAll()
            sockets.toList().forEach { runCatching { it.close() } }
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    inner class Channel(private val lane: Lane, private val dc: Int) {
        val calls = ConcurrentHashMap.newKeySet<Call>()
        private val scope = CoroutineScope(SupervisorJob() + hooks.dispatcher)
        private val slots = Semaphore(8)
        private val uploads = ByteBudget(8 * 1024 * 1024)
        private val replies = ByteBudget(8 * 1024 * 1024)
        private val queue = Queue<ByteArray>(16, onUndeliveredElement = {
            replies.release(it.size)
            lane.replies.release(it.size)
        })
        @Volatile internal var closed = false
            private set
        @Volatile private var lastProgress = hooks.clock()
        @Volatile private var delivering = false
        @Volatile private var waiting: Waiting? = null
        private val history = mutableListOf<ReplayPacket>()
        private val pending = linkedSetOf<PendingRequest>()
        private var nextSequence = 0L
        private val historyLock = Any()

        init {
            lane.channels.add(this)
            if (lane.closed || !connectivity.isCurrent(lane.epoch)) close()
        }

        /** All four limits are reserved before a packet enters the replay history. */
        private inner class Reservation(val bytes: Int) {
            var channelSlot = false
            var channelBytes = false
            var laneSlot = false
            var laneBytes = false
            private val released = java.util.concurrent.atomic.AtomicBoolean()
            fun release() {
                if (!released.compareAndSet(false, true)) return
                if (laneBytes) lane.uploads.release(bytes)
                if (laneSlot) lane.slots.release()
                if (channelBytes) uploads.release(bytes)
                if (channelSlot) slots.release()
            }
        }

        internal suspend fun send(body: ByteArray) {
            val reservation = Reservation(body.size)
            var admitted = false
            try {
                withTimeout(65_000) {
                    waiting = Waiting(Capacity.CHANNEL_SLOTS, body.size)
                    slots.acquire()
                    reservation.channelSlot = true
                    waiting = Waiting(Capacity.CHANNEL_BYTES, body.size)
                    uploads.acquire(body.size)
                    reservation.channelBytes = true
                    waiting = Waiting(Capacity.LANE_SLOTS, body.size)
                    lane.slots.acquire()
                    reservation.laneSlot = true
                    waiting = Waiting(Capacity.LANE_BYTES, body.size)
                    lane.uploads.acquire(body.size)
                    reservation.laneBytes = true
                    admitted = admit(body, null, reservation)
                }
                if (!admitted) throw CancellationException("H2 channel closed")
            } finally {
                waiting = null
                if (!admitted) reservation.release()
            }
        }

        private fun tryReserve(bytes: Int): Reservation? {
            val reservation = Reservation(bytes)
            if (slots.tryAcquire()) {
                reservation.channelSlot = true
                if (uploads.tryAcquire(bytes)) {
                    reservation.channelBytes = true
                    if (lane.slots.tryAcquire()) {
                        reservation.laneSlot = true
                        if (lane.uploads.tryAcquire(bytes)) {
                            reservation.laneBytes = true
                            return reservation
                        }
                    }
                }
            }
            reservation.release()
            return null
        }

        private fun remember(body: ByteArray, now: Long): ReplayPacket? {
            if (body.size > 64 * 1024 || body.take(8).all { it == 0.toByte() }) return null
            val key = body.copyOf(8)
            if (history.firstOrNull()?.body?.copyOf(8)?.contentEquals(key) == false) history.clear()
            history.firstOrNull { it.body.contentEquals(body) }?.let { return it }
            val packet = ReplayPacket(body, now, ++nextSequence)
            history.add(packet)
            while (history.size > 16 || history.sumOf { it.body.size } > 64 * 1024) history.removeAt(0)
            return packet
        }

        private fun admit(body: ByteArray, replay: ReplayPacket?, reservation: Reservation): Boolean {
            val request: PendingRequest
            synchronized(historyLock) {
                if (closed || lane.closed || !scope.isActive || !connectivity.isCurrent(lane.epoch)) return false
                if (replay != null && (replay !in history || pending.any {
                    it.packet === replay && (it.replay || it.receivingHeaders)
                })) return false
                val packet = replay ?: remember(body, hooks.clock())
                request = PendingRequest(body, packet, replay != null)
                request.job = scope.launch(start = CoroutineStart.LAZY) { execute(request) }
                pending.add(request)
                if (replay != null) {
                    replay.attempts++
                    replay.lastAttemptAt = hooks.clock()
                }
            }
            // Also runs for a LAZY job cancelled before its body starts.
            request.job.invokeOnCompletion {
                synchronized(historyLock) { pending.remove(request) }
                reservation.release()
            }
            request.job.start()
            return true
        }

        private suspend fun execute(request: PendingRequest) {
            try {
                val reply = lane.post(request.body, this, request)
                lastProgress = hooks.clock()
                if (reply.isNotEmpty()) {
                    synchronized(historyLock) {
                        request.packet?.let { packet ->
                            val id = reply.copyOf(minOf(24, reply.size))
                            if (packet.replyId?.contentEquals(id) != true) {
                                packet.replyId = id
                                packet.attempts = 0
                            }
                        }
                    }
                    if (!replies.tryAcquire(reply.size)) {
                        lane.replies.release(reply.size)
                        throw IOException("H2 channel response buffer limit")
                    }
                    queue.send(reply)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request.rotating || closed || !currentCoroutineContext().isActive) return
                queue.close(e)
                if (e !is H2TransportError) {
                    connectivity.recordIfCurrent(lane.epoch, requireInternet = true) {
                        retryAfter[lane.host] = System.currentTimeMillis() + 30_000
                    }
                }
                DiagnosticLogger.failure("h2_request_failed", e, "dc" to dc)
            }
        }

        private fun isHeaderWaiting(request: PendingRequest, now: Long): Boolean =
            !request.job.isCompleted && !request.rotating && !request.receivingHeaders &&
                request.sentAt?.let { now - it >= TimeUnit.SECONDS.toNanos(3) } == true

        /** Never retire a non-replayable upload or a response already being received. */
        private fun rotationCandidate(now: Long, allowReplay: Boolean = true): PendingRequest? = synchronized(historyLock) {
            pending.filter { request ->
                (allowReplay || !request.replay) && isHeaderWaiting(request, now) && (request.replay || request.packet?.let {
                    it in history && now - it.admittedAt <= TimeUnit.SECONDS.toNanos(30)
                } == true)
            }.minWithOrNull(compareBy<PendingRequest>({ !it.replay }, { it.sentAt }))
        }

        private suspend fun rotate(request: PendingRequest) {
            synchronized(historyLock) {
                if (request.rotating || request.receivingHeaders || request.job.isCompleted) return
                request.rotating = true
                if (!request.replay) request.packet?.retired = true
            }
            request.call?.cancel()
            request.job.cancelAndJoin()
        }

        private suspend fun makeUploadRoom(now: Long) {
            val blocked = waiting ?: return
            val stillBlocked = when (blocked.capacity) {
                Capacity.CHANNEL_SLOTS -> slots.availablePermits == 0
                Capacity.CHANNEL_BYTES -> uploads.availableBytes < blocked.bytes
                Capacity.LANE_SLOTS -> lane.slots.availablePermits == 0
                Capacity.LANE_BYTES -> lane.uploads.availableBytes < blocked.bytes
            }
            if (!stillBlocked) return
            val channels = if (blocked.capacity in setOf(Capacity.LANE_SLOTS, Capacity.LANE_BYTES)) {
                lane.channels.toList()
            } else listOf(this)
            channels.mapNotNull { channel -> channel.rotationCandidate(now)?.let { channel to it } }
                .minByOrNull { it.second.sentAt ?: Long.MAX_VALUE }
                ?.let { (channel, request) -> channel.rotate(request) }
        }

        internal suspend fun recoverOnce() {
            if (closed) return
            val now = hooks.clock()
            makeUploadRoom(now)
            if (closed || delivering || replies.availableBytes < 8 * 1024 * 1024) return
            val candidate = synchronized(historyLock) {
                history.filter { packet ->
                    val originals = pending.filter { it.packet === packet && !it.replay }
                    val replay = pending.firstOrNull { it.packet === packet && it.replay }
                    val overdue = packet.retired || originals.any { isHeaderWaiting(it, now) }
                    now - packet.admittedAt in 0..TimeUnit.SECONDS.toNanos(30) &&
                        packet.attempts < 3 && replay == null &&
                        (packet.lastAttemptAt?.let { now - it >= TimeUnit.SECONDS.toNanos(2) } != false) &&
                        pending.none { it.packet === packet && it.receivingHeaders } &&
                        (overdue || (packet === history.lastOrNull() && pending.isEmpty() &&
                            now - lastProgress >= TimeUnit.SECONDS.toNanos(1)))
                }.minWithOrNull(compareBy<ReplayPacket>({ !it.retired }, { it.sequence }))
            } ?: return

            // Recovery never joins the native uploader's capacity queue. Retire an
            // eligible header waiter and attempt admission once without waiting.
            if (synchronized(historyLock) { pending.count { it.replay } } >= 2) {
                val stale = synchronized(historyLock) {
                    pending.firstOrNull {
                        it.replay && isHeaderWaiting(it, now) &&
                            now - (it.sentAt ?: now) >= TimeUnit.SECONDS.toNanos(30)
                    }
                } ?: return
                rotate(stale)
            }
            var reservation = tryReserve(candidate.body.size)
            if (reservation == null) {
                val localVictim = rotationCandidate(now, allowReplay = false)
                if (localVictim != null) {
                    rotate(localVictim)
                } else {
                    lane.channels.asSequence().filter { it !== this }
                        .mapNotNull { channel -> channel.rotationCandidate(now, allowReplay = false)?.let { channel to it } }
                        .minByOrNull { it.second.sentAt ?: Long.MAX_VALUE }
                        ?.let { (channel, request) -> channel.rotate(request) }
                }
                reservation = tryReserve(candidate.body.size)
            }
            if (reservation != null && !admit(candidate.body, candidate, reservation)) reservation.release()
        }

        internal fun replayBodies(): List<ByteArray> = synchronized(historyLock) { history.map { it.body } }
        internal fun pendingRequests(): List<PendingRequest> = synchronized(historyLock) { pending.toList() }

        suspend fun bridge(input: InputStream, output: OutputStream, crypto: CryptoCtx, tag: Int, closeClient: () -> Unit) = coroutineScope {
            val upload = launch(hooks.dispatcher) {
                while (isActive) {
                    val body = NativeHttpCodec.readPacket(input, crypto.clientDecryptor::update, tag)
                    send(body)
                    connectivity.recordIfCurrent(lane.epoch) { ProxyStats.bytesUp.addAndGet(body.size.toLong()) }
                }
            }
            val download = launch(hooks.dispatcher) {
                try {
                    for (body in queue) {
                        delivering = true
                        try {
                            output.write(crypto.clientEncryptor.update(NativeHttpCodec.encodeReply(body, tag)))
                            output.flush()
                            connectivity.recordIfCurrent(lane.epoch) {
                                ProxyStats.bytesDown.addAndGet(body.size.toLong())
                                connectivity.record(lane.epoch, RouteKey(dc, true, TransportRoute.CLOUDFLARE), RoutePhase.RESPONDING)
                            }
                        } finally {
                            delivering = false
                            replies.release(body.size)
                            lane.replies.release(body.size)
                        }
                    }
                } catch (e: H2TransportError) {
                    val body = NativeHttpCodec.bytesLE(e.code)
                    output.write(crypto.clientEncryptor.update(NativeHttpCodec.encodeReply(body, tag)))
                    output.flush()
                }
            }
            val recovery = launch(hooks.dispatcher) {
                while (isActive) {
                    delay(250)
                    recoverOnce()
                }
            }
            try {
                kotlinx.coroutines.selects.select<Unit> {
                    upload.onJoin {}
                    download.onJoin {}
                    recovery.onJoin {}
                }
            } finally {
                closeBridgeEndpoints(closeClient, ::close)
                upload.cancel(); download.cancel(); recovery.cancel()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            calls.toList().forEach(Call::cancel)
            hooks.afterCloseSnapshot?.invoke()
            queue.cancel()
            scope.cancel()
            lane.channels.remove(this)
        }
    }
}

private class H2TransportError(val code: Int) : IOException("MTProto transport error")

/** Weighted, cancellable memory backpressure; no network data persists across epochs. */
class ByteBudget(private val limit: Int) {
    private var used = 0
    val availableBytes: Int get() = synchronized(this) { limit - used }
    private val changed = Queue<Unit>(Queue.CONFLATED)
    fun tryAcquire(bytes: Int): Boolean = synchronized(this) {
        require(bytes in 0..limit)
        if (used + bytes <= limit) { used += bytes; true } else false
    }
    suspend fun acquire(bytes: Int) {
        require(bytes in 0..limit)
        while (true) {
            if (tryAcquire(bytes)) {
                changed.trySend(Unit)
                return
            }
            changed.receive()
        }
    }
    fun release(bytes: Int) {
        synchronized(this) { used -= bytes }
        changed.trySend(Unit)
    }
}

/** Native framing differs from WebSocket relay framing: strip quick ACK bits and padding. */
internal object NativeHttpCodec {
    const val MAX_PACKET = 4 * 1024 * 1024
    fun intLE(data: ByteArray): Int = (data[0].toInt() and 255) or ((data[1].toInt() and 255) shl 8) or
        ((data[2].toInt() and 255) shl 16) or (data[3].toInt() shl 24)
    fun bytesLE(value: Int): ByteArray = ByteArray(4) { (value ushr (it * 8)).toByte() }
    fun readPacket(input: InputStream, decrypt: (ByteArray) -> ByteArray, tag: Int): ByteArray {
        fun read(size: Int): ByteArray {
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(bytes, offset, size - offset)
                if (count < 0) throw java.io.EOFException()
                offset += count
            }
            return decrypt(bytes)
        }
        val length = if (tag == MtProtoConstants.TAG_ABRIDGED) {
            val first = read(1)[0].toInt() and 127
            val words = if (first == 127) {
                val rest = read(3)
                (rest[0].toInt() and 255) or ((rest[1].toInt() and 255) shl 8) or ((rest[2].toInt() and 255) shl 16)
            } else first
            words * 4
        } else intLE(read(4)) and Int.MAX_VALUE
        require(length in 24..(MAX_PACKET + if (tag == MtProtoConstants.TAG_PADDED_INTERMEDIATE) 256 else 0))
        var body = read(length)
        if (tag == MtProtoConstants.TAG_PADDED_INTERMEDIATE) {
            val plain = body.take(8).all { it == 0.toByte() }
            val packetLength = if (plain) 20L + intLE(body.copyOfRange(16, 20)).toLong() else
                (24 + (length - 24) / 16 * 16).toLong()
            require(packetLength in 24..length.toLong() && length - packetLength <= if (plain) 256 else 15)
            body = body.copyOf(packetLength.toInt())
        }
        require(body.size % 4 == 0 && body.size <= MAX_PACKET)
        return body
    }
    fun encodeReply(body: ByteArray, tag: Int): ByteArray {
        require(body.isNotEmpty() && body.size % 4 == 0 && body.size <= MAX_PACKET)
        if (tag != MtProtoConstants.TAG_ABRIDGED) return bytesLE(body.size) + body
        val words = body.size / 4
        return (if (words < 127) byteArrayOf(words.toByte()) else byteArrayOf(127) + bytesLE(words).copyOf(3)) + body
    }
}
