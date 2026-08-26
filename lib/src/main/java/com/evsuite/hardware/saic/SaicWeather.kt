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
    private const val TX_QUERY_FORECAST_BY_POSITION = 30
    private const val TX_CALLBACK_ON_RESULT = 1

    /**
     * `Result` carries its payload behind a type tag, and only this one is decoded. Another
     * tag is another shape, and answering with a half-read parcel would be worse than saying
     * nothing.
     */
    private const val DATA_OBJECT_WEATHER = "data_object_weather"
    private const val DATA_OBJECT_WEATHER_FUTURE = "data_object_weather_future"

    /**
     * How long a reading may hold the rule cycle.
     *
     * The query can reach the network, so it is not instant, and a rule evaluated at ignition
     * would otherwise wait on a cold radio. Two seconds is longer than a warm answer and short
     * enough that a cold one simply comes back unreadable.
     */
    private const val TIMEOUT_MS = 2_000L

    /**
     * A daily outlook is a handful of days. A larger count means the parcel is not what this
     * expects, and reading on would consume whatever follows it as strings.
     */
    private const val MAX_FORECAST_DAYS = 16

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
    fun currentAt(latitude: Double, longitude: Double, languageCode: String): Reading? =
        // Longitude first, then latitude, and both as strings — the query takes them in that
        // order, so swapping them would ask about a plausible-looking wrong place rather than
        // failing outright.
        query(TX_QUERY_WEATHER_BY_POSITION, ::readCurrent) { target, callback ->
            SaicAidl.callVoid(
                target, DESCRIPTOR, TX_QUERY_WEATHER_BY_POSITION,
                longitude.toString(), latitude.toString(), languageCode, callback
            )
        }

    /**
     * The days ahead, today first.
     *
     * **Days, not hours.** The service answers a daily outlook — a phrase and a high/low per
     * day — so "will it rain tomorrow" is answerable here and "will it rain in three hours" is
     * not. A rule that wants to close the windows before a shower is not served by this, and
     * nothing in the catalogue pretends otherwise.
     *
     * The position arrives as doubles on this transaction where the current-conditions query
     * takes strings. That is the interface, not a choice.
     */
    fun forecastAt(latitude: Double, longitude: Double, languageCode: String): List<Day>? =
        query(TX_QUERY_FORECAST_BY_POSITION, ::readForecast) { target, callback ->
            SaicAidl.callVoid(
                target, DESCRIPTOR, TX_QUERY_FORECAST_BY_POSITION,
                longitude, latitude, languageCode, callback
            )
        }

    /**
     * One query, made synchronous.
     *
     * The service takes a callback binder and answers on it, so every reading here is a send
     * plus a bounded wait. A binder that never calls back leaves the latch untripped and the
     * reading null, which the catalogue treats as "cannot tell" — the same as any other
     * unreadable signal.
     */
    private fun <T> query(
        code: Int,
        decode: (Parcel) -> T?,
        send: (IBinder, Binder) -> Boolean
    ): T? {
        val target = binder() ?: return null
        var answer: T? = null
        val answered = CountDownLatch(1)

        val callback = object : Binder() {
            override fun onTransact(tx: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (tx != TX_CALLBACK_ON_RESULT) return super.onTransact(tx, data, reply, flags)
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                answer = runCatching { decode(data) }
                    .onFailure { AppLogger.d(TAG, "weather#$code: undecodable answer: ${it.message}") }
                    .getOrNull()
                answered.countDown()
                reply?.writeNoException()
                return true
            }

            override fun getInterfaceDescriptor(): String = CALLBACK_DESCRIPTOR
        }

        if (!send(target, callback)) return null
        return if (answered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) answer else null
    }

    /**
     * `Result`: a code, a message, a tag naming what follows, then the tagged object.
     *
     * Read by hand because the classes it names live in the head unit's own APKs, not on the
     * boot classpath — the same reason every transaction here is written out rather than
     * called through an SDK.
     */
    private fun readCurrent(parcel: Parcel): Reading? {
        if (!openPayload(parcel, DATA_OBJECT_WEATHER)) return null
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
     * The daily outlook: an envelope, a place, then the list of days.
     *
     * The nine strings before the list are the place and the provider's headline — read past
     * rather than skipped, because a parcel has no field names and the only way to reach the
     * list is to consume exactly what precedes it.
     */
    private fun readForecast(parcel: Parcel): List<Day>? {
        if (!openPayload(parcel, DATA_OBJECT_WEATHER_FUTURE)) return null
        parcel.readInt()                       // the payload's own code
        parcel.readString()                    // the nested parcelable's class name
        repeat(9) { parcel.readString() }      // place, coordinates and headline
        val days = parcel.readInt()
        // A negative or absurd count is a parcel that does not hold what this expects; reading
        // on would consume whatever follows as strings.
        if (days <= 0 || days > MAX_FORECAST_DAYS) return null
        return (0 until days).map {
            val date = parcel.readString().orEmpty()
            val maxValue = parcel.readString()
            parcel.readString()                // night icon
            parcel.readString()                // day icon
            parcel.readString()                // epoch date
            val nightPhrase = parcel.readString().orEmpty()
            val maxUnit = parcel.readString()
            val minUnit = parcel.readString()
            parcel.readString()                // max unit type
            val minValue = parcel.readString()
            parcel.readString()                // min unit type
            val dayPhrase = parcel.readString().orEmpty()
            Day(
                date = date,
                dayText = dayPhrase,
                nightText = nightPhrase,
                highCelsius = toCelsius(maxValue?.toDoubleOrNull(), maxUnit),
                lowCelsius = toCelsius(minValue?.toDoubleOrNull(), minUnit)
            )
        }
    }

    /**
     * Reads the `Result` envelope and stops unless it holds the expected payload.
     *
     * A code, a message, a tag naming what follows, then the tagged object. Another tag is
     * another shape, and answering from a half-read parcel would be worse than saying nothing.
     */
    private fun openPayload(parcel: Parcel, expectedTag: String): Boolean {
        parcel.readInt()                       // code — the payload's presence is the real answer
        parcel.readString()                    // message
        if (parcel.readString() != expectedTag) return false
        parcel.readString()                    // the parcelable's own class name
        return true
    }

    /**
     * The service answers in whatever unit it was configured with and names it, so the name is
     * what decides. Anything it does not name is left alone rather than assumed Celsius: a
     * Fahrenheit value passed through unconverted would read as a mild summer day in winter.
     */
    private fun toCelsius(value: Double?, unit: String?): Double? {
        if (value == null) return null
        return when (unit?.trim()?.uppercase()) {
            "C", "°C" -> value
            "F", "°F" -> (value - 32.0) * 5.0 / 9.0
            else -> null
        }
    }

    /**
     * One answer.
     *
     * [text] is the provider's own description ("Rain", "Partly cloudy"), which is what a rule
     * compares against; [temperatureCelsius] is null when the answer named no unit.
     */
    /**
     * One day of the outlook.
     *
     * [dayText] and [nightText] are the provider's phrases, in the language the query asked
     * for. The temperatures are null when the answer named no unit — see [toCelsius].
     */
    data class Day(
        val date: String,
        val dayText: String,
        val nightText: String,
        val highCelsius: Double?,
        val lowCelsius: Double?
    )

    data class Reading(
        val text: String,
        val icon: String,
        val temperatureCelsius: Double?,
        val city: String
    )
}
