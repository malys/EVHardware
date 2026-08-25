package com.evsuite.hardware.saic

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.evsuite.hardware.AppLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The head unit's own weather, from the service its map stack asks.
 *
 * The launcher shows weather too, but it broadcasts what it has for the city it settled on.
 * This is the source underneath: a query for a position, answered for that position. A rule
 * about rain is about where the car is, so the position query is the one worth binding.
 *
 * **Asynchronous, unlike every other service here.** The call takes a callback binder and
 * answers on it, so the reading is made synchronous with a bounded wait — a rule cycle has to
 * finish, and a network round-trip inside the head unit must not hold it open. A query that
 * does not answer in time is a null reading, which the catalogue already treats as
 * "unreadable" rather than as "no rain".
 *
 * Binding contract: `com.saicmotor.mapservice.SaicService` (bind target),
 * `ISaicService` (the queries) and `ICallBack` (the single-method answer).
 */
object SaicWeather {

    private const val TAG = "EV_SAIC"

    private const val PACKAGE = "com.saicmotor.mapservice"
    private const val CLASS = "com.saicmotor.mapservice.SaicService"
    private const val DESCRIPTOR = "com.saicmotor.mapservice.ISaicService"
    private const val CALLBACK_DESCRIPTOR = "com.saicmotor.mapservice.ICallBack"

    private const val TX_QUERY_WEATHER_BY_POSITION = 2
    private const val TX_CALLBACK_ON_RESULT = 1

    /**
     * `Result` carries its payload behind a type tag, and only this one is decoded. Another
     * tag is another shape, and answering with a half-read parcel would be worse than saying
     * nothing.
     */
    private const val DATA_OBJECT_WEATHER = "data_object_weather"

    /**
     * How long a reading may hold the rule cycle.
     *
     * The query can reach the network, so it is not instant, and a rule evaluated at ignition
     * would otherwise wait on a cold radio. Two seconds is longer than a warm answer and short
     * enough that a cold one simply comes back unreadable.
     */
    private const val TIMEOUT_MS = 2_000L

    private val service = SaicService.byComponent(PACKAGE, CLASS, "weather")

    val isAvailable: Boolean get() = binder() != null

    fun connect(context: Context) = service.connect(context)

    private fun binder(): IBinder? = service.binder()

    /**
     * Current conditions where the car is.
     *
     * [languageCode] reaches the provider and decides the language of [Reading.text], which is
     * the field a rule compares against — so it is the caller's, not a constant here.
     */
    fun currentAt(latitude: Double, longitude: Double, languageCode: String): Reading? {
        val target = binder() ?: return null
        var reading: Reading? = null
        val answered = CountDownLatch(1)

        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != TX_CALLBACK_ON_RESULT) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                reading = runCatching { readResult(data) }
                    .onFailure { AppLogger.d(TAG, "weather: undecodable answer: ${it.message}") }
                    .getOrNull()
                answered.countDown()
                reply?.writeNoException()
                return true
            }

            override fun getInterfaceDescriptor(): String = CALLBACK_DESCRIPTOR
        }

        // Longitude first, then latitude: the query takes them in that order, and both as
        // strings — swapping them would ask about a plausible-looking wrong place rather
        // than failing.
        val sent = SaicAidl.callVoid(
            target, DESCRIPTOR, TX_QUERY_WEATHER_BY_POSITION,
            longitude.toString(), latitude.toString(), languageCode, callback
        )
        if (!sent) return null
        return if (answered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) reading else null
    }

    /**
     * `Result`: a code, a message, a tag naming what follows, then the tagged object.
     *
     * Read by hand because the classes it names live in the head unit's own APKs, not on the
     * boot classpath — the same reason every transaction here is written out rather than
     * called through an SDK.
     */
    private fun readResult(parcel: Parcel): Reading? {
        parcel.readInt()                       // code — the payload's presence is the real answer
        parcel.readString()                    // message
        if (parcel.readString() != DATA_OBJECT_WEATHER) return null
        parcel.readString()                    // the parcelable's own class name
        val text = parcel.readString()
        val icon = parcel.readString()
        parcel.readByte()                      // daytime
        val temperature = parcel.readDouble()
        val unit = parcel.readString()
        parcel.readInt()                       // unit type
        val city = parcel.readString()
        if (text.isNullOrBlank()) return null
        return Reading(
            text = text,
            icon = icon.orEmpty(),
            temperatureCelsius = toCelsius(temperature, unit),
            city = city.orEmpty()
        )
    }

    /**
     * The service answers in whatever unit it was configured with and names it, so the name is
     * what decides. Anything it does not name is left alone rather than assumed Celsius: a
     * Fahrenheit value passed through unconverted would read as a mild summer day in winter.
     */
    private fun toCelsius(value: Double, unit: String?): Double? = when (unit?.trim()?.uppercase()) {
        "C", "°C" -> value
        "F", "°F" -> (value - 32.0) * 5.0 / 9.0
        else -> null
    }

    /**
     * One answer.
     *
     * [text] is the provider's own description ("Rain", "Partly cloudy"), which is what a rule
     * compares against; [temperatureCelsius] is null when the answer named no unit.
     */
    data class Reading(
        val text: String,
        val icon: String,
        val temperatureCelsius: Double?,
        val city: String
    )
}
