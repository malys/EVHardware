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
 * It is also, since the owner scoped the read-only rule to the car's driving and safety
 * settings, where a route goes *out* to the navigation app: [startNavFromEvRoute] is the one
 * call in this object that sends. Everything else here listens or asks.
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

    // Synchronous getters on the same interface. SaicNav's note that the head unit answers no
    // synchronous question about a trip was read off IMapService; IGeneralService does answer.
    private const val TX_IS_MAP_NAVIGATING = 18
    private const val TX_GET_ROAD_NAME = 29
    private const val TX_GET_GUIDE_STATUS = 31
    private const val TX_GET_REMAINING_TIMES = 33
    private const val TX_GET_REMAINING_DISTANCE = 34

    // The two transactions here that send rather than ask. See [goTo], [startNavFromEvRoute].
    private const val TX_GO_TO = 23
    private const val TX_START_NAV_FROM_EV_ROUTE = 48

    private val service = SaicService.byComponent(PACKAGE, CLASS, "nav-guidance")

    /**
     * The folded state. Written only from a binder thread, read from anywhere, so it is
     * volatile and the value it points at is immutable.
     */
    @Volatile
    private var state: NavGuidance = NavGuidance.EMPTY

    @Volatile
    private var registered: Boolean = false

    /**
     * Every transaction code the adapter sent, decoded or not.
     *
     * The transaction map came from an R69 build and a vehicle may run another revision, so
     * this is what proves the map still lines up. It is read by a capture, never by a value.
     */
    private val census = TransactionCensus()

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
     * The current guidance, read rather than waited for.
     *
     * The five callbacks are change notifications: a car standing still with a guidance
     * already running publishes nothing, because nothing changed. That made a parked capture
     * unable to tell "the adapter says nothing" from "nothing happened to say", which is the
     * distinction CP-040 turns on. These getters answer on demand instead.
     *
     * Values seen so far by the listener are kept where a getter answers nothing, so a caller
     * gets the best of both without choosing between them.
     */
    fun readNow(): NavGuidance {
        val target = binder() ?: return state
        fun int(code: Int) = SaicAidl.callInt(target, DESCRIPTOR, code)
        // The service initialises these to 0 and answers 0 before any guidance has run, which
        // is the same answer an arrived route would give. Unknown is the cheaper reading: a
        // remaining distance of exactly zero is not a case any forecast needs.
        val distance = int(TX_GET_REMAINING_DISTANCE)?.takeIf { it > 0 }
        val minutes = int(TX_GET_REMAINING_TIMES)?.takeIf { it > 0 }
        val status = int(TX_GET_GUIDE_STATUS)
        val road = SaicAidl.callString(target, DESCRIPTOR, TX_GET_ROAD_NAME)?.takeIf {
            it.isNotBlank()
        }
        val seen = state
        return seen.copy(
            guideStatus = status ?: seen.guideStatus,
            remainingDistanceRaw = distance ?: seen.remainingDistanceRaw,
            remainingMinutes = minutes ?: seen.remainingMinutes,
            road = road ?: seen.road,
        )
    }

    /**
     * Whether the head unit is guiding, as the navigation app itself says so.
     *
     * Not an inference from a distance or a road name: `MapService` keeps this flag and the
     * navigation app sets it through `IMapService.isMapNavigating(boolean)`, its own report of
     * its own state. That makes it the one thing on this interface that can confirm a handoff
     * worked — a command accepted by the adapter says only that the adapter took it, and the
     * fan-out swallows a refusal, so this flag is the difference between "sent" and "started".
     *
     * @return null when the adapter is not there to ask.
     */
    fun isMapNavigating(): Boolean? =
        SaicAidl.callBoolean(binder(), DESCRIPTOR, TX_IS_MAP_NAVIGATING)

    /**
     * Asks the navigation app to guide to one point — the vendor's own "take me there".
     *
     * **Why this exists beside [startNavFromEvRoute].** The route handoff put the destination on
     * the map and left it there: the driver saw the place and no guidance ever started. `goTo` is
     * the other command, and it is the one the head unit's voice assistant uses — `GeneralService`
     * turns it into `MapService.goToPoi(null, address, null, latitude, longitude)`, the same call
     * `IVoiceVuiService` makes when someone says "emmène-moi à". Where the route handoff hands
     * over points to draw, this one names a destination to drive to.
     *
     * **One point, no pathway.** The interface has room for nothing else, so a plan with a
     * charging stop must choose: guide to the stop, which is the leg being driven, and leave the
     * rest of the plan to the screen that planned it.
     *
     * Same boundary as [startNavFromEvRoute] — a navigation write, not a vehicle write — and the
     * same two cautions: the adapter logs the point, and this is a synchronous fan-out that must
     * not run on the main thread.
     *
     * @return true when the adapter took the call, which is not the map confirming it obeyed.
     */
    fun goTo(point: NavigationHandoff.Poi): Boolean =
        SaicAidl.callWriting(binder(), DESCRIPTOR, TX_GO_TO) { data -> data.writeGoTo(point) }

    /**
     * `goTo(String address, double latitude, double longitude)`, in the order the adapter's stub
     * reads it. Latitude before longitude, and the name first: swap any two and the car drives to
     * a place nobody asked for, which no compiler and no crash would have said.
     */
    internal fun Parcel.writeGoTo(point: NavigationHandoff.Poi) {
        writeString(point.name)
        writeDouble(point.latitude)
        writeDouble(point.longitude)
    }

    /**
     * Hands a planned route to whichever navigation app this head unit runs.
     *
     * **The one write in this object, and it is not a vehicle write.** `VehicleWriteGate` exists
     * for the settings that change how the car behaves under the driver — AEB, ELK, ACC/TJA, the
     * drive mode — and gates them on standstill. A destination is not one of those any more than
     * the comfort writes that gate already exempts: it moves a map, and the driver asked for it.
     *
     * **It is a call in, not an impersonation.** `IMapNotificationListener` is the channel the
     * head unit uses to *command* the navigation app, and registering on it to send one
     * destination would mean claiming to be a navigation provider. This is the other end of the
     * same wire: `IGeneralService` transaction 48 hands the points to `GeneralService`, which
     * forwards to `MapService`, which fans them out over its `RemoteCallbackList` to the app
     * that did register. The navigation app stays the navigation app.
     *
     * **What leaves the car.** Two lists of latitude, longitude and name — the pathway, then the
     * destination — and nothing else; `EVRoutPoiInfo` has no other field. They do not leave the
     * *car*, in fact: this is one binder transaction to a service on the same head unit.
     * **The adapter logs them.** `MapService.startNavFromEVRout` writes the whole of both lists
     * to the head unit's own logcat before fanning out. That is the vendor's log, not this
     * app's, and it is the one place a destination appears in text — worth knowing, not worth
     * refusing a working handoff over.
     *
     * **Call it off the main thread.** The transaction is not `oneway`, and the service fans out
     * to every registered listener, synchronously, while holding the lock on its callback list.
     *
     * @param destination where the driver is going.
     * @param pathway points to pass through on the way — the charging stop, in this app's case.
     *   Capped by [NavigationHandoff.pathway] before it gets here.
     * @return true when the adapter took the call. It does not mean the map drew a route: the
     *   fan-out swallows whatever the navigation app throws, so a listener that refused the
     *   points is indistinguishable from one that followed them. Only the driver can say.
     */
    fun startNavFromEvRoute(
        destination: NavigationHandoff.Poi,
        pathway: List<NavigationHandoff.Poi> = emptyList(),
    ): Boolean = SaicAidl.callWriting(binder(), DESCRIPTOR, TX_START_NAV_FROM_EV_ROUTE) { data ->
        data.writeEvRoutPoiList(pathway)
        data.writeEvRoutPoiList(listOf(destination))
    }

    /**
     * A `List<EVRoutPoiInfo>` as `Parcel.writeTypedList` lays it out: the count, then per item a
     * non-null marker and the bean's own three fields in the order its `writeToParcel` wrote
     * them. Written by hand because the bean is the vendor's class and this module does not
     * carry it — the layout is three lines and depending on a decompiled class would be worse.
     */
    internal fun Parcel.writeEvRoutPoiList(pois: List<NavigationHandoff.Poi>) {
        writeInt(pois.size)
        pois.forEach { poi ->
            writeInt(1)
            writeDouble(poi.latitude)
            writeDouble(poi.longitude)
            writeString(poi.name)
        }
    }

    /** Transaction codes seen so far, with counts. See [TransactionCensus]. */
    fun census(): Map<Int, Int> = census.snapshot()

    /** Codes the census could not index. Non-zero means the interface is not the one expected. */
    fun censusBeyondCeiling(): Int = census.beyondCeiling

    /** The last parcel size seen on each code. See [TransactionCensus.payloadBytes]. */
    fun censusPayloadBytes(): Map<Int, Int> = census.payloadBytes()

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
        census.clear()
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
            // The size before anything is read: `dataPosition` is zero here, so this is the whole
            // parcel. Recorded for every code, decoded or not — the undecoded ones are exactly
            // the ones whose shape nobody knows.
            census.record(code, runCatching { data.dataSize() }.getOrDefault(-1))
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
