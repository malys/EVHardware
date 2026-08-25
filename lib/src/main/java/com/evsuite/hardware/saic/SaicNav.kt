package com.evsuite.hardware.saic

import android.content.Context
import android.os.IBinder

/**
 * The head unit's navigation adapter — the service its map, cluster and voice apps share.
 *
 * A different bind from [SaicHub]: the vehicle hub answers about the car's own systems,
 * while this one sits between the head unit and whichever navigation app is installed. What
 * is useful here is the small set of things it answers *synchronously*; the rest of its
 * surface is a stream of callbacks from the running navigation app, which is a subscription
 * rather than a reading and is deliberately not wired up.
 *
 * That distinction is the reason trip distance is absent. Remaining distance and time only
 * arrive as notifications *into* this service from the navigation app, so reading them means
 * registering as a listener and holding that registration for the life of the process.
 * [totalMileageKm] is a getter, and the odometer is what a "service due" rule actually wants.
 */
object SaicNav {

    private const val PACKAGE = "com.saicmotor.adapterservice"
    private const val CLASS = "com.saicmotor.adapterservice.services.MapService"
    private const val DESCRIPTOR = "com.saicmotor.adapterservice.IMapService"

    private const val TX_GET_TOTAL_MILEAGE = 11

    private val service = SaicService.byComponent(PACKAGE, CLASS, "nav-adapter")

    val isAvailable: Boolean get() = binder() != null

    fun connect(context: Context) = service.connect(context)

    private fun binder(): IBinder? = service.binder()

    /**
     * The odometer, in kilometres.
     *
     * The service answers 0 when its own energy manager is not up, which is the same answer a
     * brand-new car would give — so 0 is treated as unreadable. A car that has genuinely
     * driven nothing is not a case any rule needs, and reporting "unknown" there is the
     * cheaper mistake.
     *
     * Kilometres because that is the signal; the head unit's distance-unit setting changes
     * what its screens display, not what the bus carries.
     */
    fun totalMileageKm(): Int? =
        SaicAidl.callInt(binder(), DESCRIPTOR, TX_GET_TOTAL_MILEAGE)?.takeIf { it > 0 }
}
