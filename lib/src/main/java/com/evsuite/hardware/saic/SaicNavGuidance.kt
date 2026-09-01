package com.evsuite.hardware.saic

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.evsuite.hardware.AppLogger

/**
 * Remaining distance and time to the destination, as the head unit's own navigation reports it.
 *
 * [SaicNav] explains why the map adapter answers no synchronous question about a trip: the
 * navigation app *pushes* guidance into the adapter service, which fans it out to whoever
 * registered. This is that registration, and it is the only read-only route source found on
 * the vehicle that costs no Android permission and no network.
 *
 * **The service does not wait politely.** `IGeneralNotificationListener` is not `oneway` —
 * the proxy calls `transact(code, data, reply, 0)` and then `readException()`, and the
 * adapter fans out to every listener while holding the lock on its callback list. A slow
 * listener therefore stalls the instrument cluster, not just this app. Everything
 * [onTransact][ListenerBinder.onTransact] does is one parcel read and one volatile write; it
 * must stay that way. No I/O, no lock, no allocation beyond the state object, ever.
 *
 * **Registration is additive.** The adapter keeps a `RemoteCallbackList`, so registering here
 * neither displaces the navigation app nor the cluster, and the list drops this listener on
 * its own if the process dies.
 *
 * **Development instrument.** Nothing here is validated on a vehicle: the distance callbacks
 * carry no unit and the status codes carry no documented meaning, so [latest] is evidence to
 * be captured and read, not a value to display. CP-040 is the ticket that turns it into
 * either a decision or a deletion.
 */
object SaicNavGuidance {

    private const val TAG = "EV_SAIC"

    private const val PACKAGE = "com.saicmotor.adapterservice"
    private const val CLASS = "com.saicmotor.adapterservice.services.GeneralService"
    private const val DESCRIPTOR = "com.saicmotor.adapterservice.IGeneralService"
    private const val LISTENER_DESCRIPTOR =
        "com.saicmotor.adapterservice.IGeneralNotificationListener"

    private const val TX_REGISTER_LISTENER = 1
    private const val TX_UNREGISTER_LISTENER = 2

    private val service = SaicService.byComponent(PACKAGE, CLASS, "nav-guidance")

    /**
     * The folded state. Written only from a binder thread, read from anywhere, so it is
     * volatile and the value it points at is immutable.
     */
    @Volatile
    private var state: NavGuidance = NavGuidance.EMPTY

    @Volatile
    private var registered: Boolean = false

    val isAvailable: Boolean get() = binder() != null

    /** True once the adapter accepted this listener. */
    val isListening: Boolean get() = registered

    fun connect(context: Context) = service.connect(context)

    private fun binder(): IBinder? = service.binder()

    /**
     * The last guidance seen, or [NavGuidance.EMPTY] before anything arrived.
     *
     * `events == 0` means the listener registered but the navigation app has said nothing —
     * which is the answer when no guidance is running, and is itself the finding CP-040 needs.
     */
    fun latest(): NavGuidance = state

    /**
     * Starts listening. Idempotent.
     *
     * @return true when the adapter accepted the registration.
     */
    fun start(): Boolean {
        if (registered) return true
        val target = binder() ?: return false
        val accepted = SaicAidl.callVoid(target, DESCRIPTOR, TX_REGISTER_LISTENER, listener)
        registered = accepted
        if (!accepted) AppLogger.w(TAG, "nav-guidance: registration refused")
        return accepted
    }

    /**
     * Stops listening and forgets what was seen.
     *
     * Callers must reach this on the way out of any screen that called [start]: a listener
     * left registered keeps this process on the adapter's fan-out path for no reason.
     */
    fun stop() {
        val target = binder()
        if (registered && target != null) {
            SaicAidl.callVoid(target, DESCRIPTOR, TX_UNREGISTER_LISTENER, listener)
        }
        registered = false
        state = NavGuidance.EMPTY
    }

    /**
     * The callback the adapter calls.
     *
     * Unrecognised transactions are answered without being parsed. Letting them fall through
     * to `super.onTransact` would return false, which the adapter turns into an exception it
     * logs for every unrelated callback it makes — noise on the head unit's log for no gain.
     */
    private val listener = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in NavGuidanceReducer.KNOWN_TRANSACTIONS) {
                reply?.writeNoException()
                return true
            }
            runCatching {
                data.enforceInterface(LISTENER_DESCRIPTOR)
                state = when (code) {
                    NavGuidanceReducer.TX_GUIDE_INFOS_CHANGE -> NavGuidanceReducer.fold(
                        state, code,
                        first = data.readInt(),
                        second = data.readInt(),
                        text = data.readString(),
                        atElapsedMs = SystemClock.elapsedRealtime(),
                    )
                    NavGuidanceReducer.TX_ROAD_INFO_CHANGE -> NavGuidanceReducer.fold(
                        state, code,
                        text = data.readString(),
                        atElapsedMs = SystemClock.elapsedRealtime(),
                    )
                    else -> NavGuidanceReducer.fold(
                        state, code,
                        first = data.readInt(),
                        atElapsedMs = SystemClock.elapsedRealtime(),
                    )
                }
            }.onFailure {
                // A payload shaped differently on another firmware is a null reading, not a
                // crash on the adapter's fan-out thread.
                AppLogger.d(TAG, "nav-guidance#$code: undecodable: ${it.message}")
            }
            reply?.writeNoException()
            return true
        }

        override fun getInterfaceDescriptor(): String = LISTENER_DESCRIPTOR
    }
}
