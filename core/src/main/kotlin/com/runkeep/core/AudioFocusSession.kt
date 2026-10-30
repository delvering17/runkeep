package com.runkeep.core

enum class AudioFocusChange { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK }

/** A request owns its exact platform handle, including its callback generation. */
interface AudioFocusRequestHandle {
    fun request(): Boolean
    fun abandon()
}

interface AudioFocusBackend {
    fun playbackAllowed(): Boolean
    fun create(policy: MusicFocusPolicy, onChange: (AudioFocusChange) -> Unit): AudioFocusRequestHandle
}

/** Single-threaded session. Players own playback and their permanent interruption latches. */
class AudioFocusSession(private val backend: AudioFocusBackend, initialPolicy: MusicFocusPolicy = MusicFocusPolicy.DUCK) {
    private var currentPolicy = initialPolicy
    val policy get() = currentPolicy
    private val clients = mutableSetOf<Client>()
    private val owners = mutableSetOf<Client>()
    private var request: AudioFocusRequestHandle? = null
    private var requestedPolicy: MusicFocusPolicy? = null
    private var generation = 0L
    private var focused = false
    private var suspended = false
    private var permanentLoss = false
    private var closed = false

    fun client(onChange: (AudioFocusChange) -> Unit): Client {
        check(!closed)
        return Client(onChange).also { clients += it }
    }

    /** Suspended/phone-blocked owners keep the old request until a real, allowed gain. */
    fun setPolicy(value: MusicFocusPolicy) {
        if (closed || policy == value) return
        currentPolicy = value
        if (owners.isEmpty() || suspended || permanentLoss || !backend.playbackAllowed()) return
        retireRequest()
        if (!requestFocus()) interruptPermanently()
    }

    private fun requestFocus(): Boolean {
        val token = ++generation
        val next = runCatching { backend.create(policy) { change -> changed(token, change) } }.getOrNull() ?: return false
        request = next
        requestedPolicy = policy
        // A synchronous loss must not be overwritten by the request's return value.
        focused = true
        val granted = runCatching { next.request() }.getOrDefault(false)
        if (!granted || request !== next || !focused || suspended || permanentLoss) {
            if (request === next) retireRequest()
            return false
        }
        return true
    }

    private fun changed(token: Long, change: AudioFocusChange) {
        if (closed || token != generation || request == null) return
        when (change) {
            AudioFocusChange.GAIN -> {
                if (permanentLoss || !backend.playbackAllowed()) return
                if (requestedPolicy != policy) {
                    retireRequest()
                    suspended = false
                    if (!requestFocus()) { interruptPermanently(); return }
                }
                focused = true
                suspended = false
            }
            AudioFocusChange.LOSS -> { interruptPermanently(); return }
            AudioFocusChange.LOSS_TRANSIENT, AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK -> {
                focused = false
                suspended = true
            }
        }
        notifyOwners(change)
    }

    fun interruptPermanently() {
        interrupt(owners.toList())
    }

    /** Route loss also stops clients waiting for focus, before they retry on the speaker. */
    fun routeLost() {
        interrupt(clients.toList())
    }

    private fun interrupt(recipients: List<Client>) {
        if (closed) return
        permanentLoss = true
        suspended = false
        retireRequest()
        recipients.forEach { if (!it.disposed) it.onChange(AudioFocusChange.LOSS) }
        if (owners.isEmpty()) permanentLoss = false
    }

    private fun notifyOwners(change: AudioFocusChange) {
        // stop/release/close in a callback cannot invalidate iteration over other owners.
        owners.toList().forEach { if (!it.disposed) it.onChange(change) }
    }

    private fun retireRequest() {
        val previous = request
        request = null
        requestedPolicy = null
        focused = false
        generation++
        // Invalidate before abandon: an old platform callback cannot touch a new request.
        if (previous != null) runCatching { previous.abandon() }
    }

    inner class Client internal constructor(internal val onChange: (AudioFocusChange) -> Unit) {
        internal var disposed = false; private set

        fun acquire(): Boolean {
            if (closed || disposed || suspended || permanentLoss || !backend.playbackAllowed()) return false
            if (!focused || requestedPolicy != policy) {
                retireRequest()
                if (!requestFocus()) {
                    if (owners.isEmpty()) { suspended = false; permanentLoss = false }
                    else interruptPermanently()
                    return false
                }
            }
            owners += this
            return true
        }

        fun release() {
            owners -= this
            if (owners.isEmpty()) {
                retireRequest()
                suspended = false
                permanentLoss = false
            }
        }

        fun close() {
            if (disposed) return
            disposed = true
            release()
            clients -= this
        }
    }

    fun close() {
        if (closed) return
        closed = true
        retireRequest()
        clients.toList().forEach { it.close() }
    }
}
