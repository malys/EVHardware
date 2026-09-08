package com.evsuite.hardware.saic

import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one thing about [SaicNavGuidance.startNavFromEvRoute] that can be proven without a car.
 *
 * The adapter reads the argument with `Parcel.createTypedArrayList(EVRoutPoiInfo.CREATOR)`, and
 * this module writes it by hand — it does not carry the vendor's bean. A layout that is wrong by
 * one int is not a compile error and not a crash here: it is an adapter that reads a length off
 * the wrong offset and hands the navigation app a route to nowhere.
 *
 * So the layout is compared against what Android's own `writeTypedList` produces for a bean with
 * the same three fields in the same order, which is exactly what `EVRoutPoiInfo.writeToParcel`
 * is. If a platform release ever changes that framing, this test says so rather than a drive.
 */
@RunWith(RobolectricTestRunner::class)
// The head unit runs API 28; 34 is what this module's other Robolectric test pins and what
// the sandbox has. Parcel framing has not moved between them, which is the point of the test.
@Config(sdk = [34])
class EvRoutPoiParcelTest {

    /** `EVRoutPoiInfo`'s own serialisation: latitude, longitude, name. Nothing else is in it. */
    private class Stand(val poi: NavigationHandoff.Poi) : Parcelable {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeDouble(poi.latitude)
            dest.writeDouble(poi.longitude)
            dest.writeString(poi.name)
        }
    }

    private fun handWritten(pois: List<NavigationHandoff.Poi>): ByteArray =
        bytes { parcel -> with(SaicNavGuidance) { parcel.writeEvRoutPoiList(pois) } }

    private fun framework(pois: List<NavigationHandoff.Poi>): ByteArray =
        bytes { parcel -> parcel.writeTypedList(pois.map(::Stand)) }

    private fun bytes(write: (Parcel) -> Unit): ByteArray {
        val parcel = Parcel.obtain()
        return try {
            write(parcel)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    @Test fun `one destination is framed the way the adapter reads it`() {
        val pois = listOf(NavigationHandoff.Poi(43.5583, 1.5333, "Auzielle"))
        assertArrayEquals(framework(pois), handWritten(pois))
    }

    @Test fun `a pathway of several stops keeps its order and its framing`() {
        val pois = listOf(
            NavigationHandoff.Poi(43.343, 3.215, "Ionity Beziers Est"),
            NavigationHandoff.Poi(44.128, 4.081, "Ales"),
        )
        assertArrayEquals(framework(pois), handWritten(pois))
    }

    @Test fun `an empty pathway is a count of zero, not an absent argument`() {
        // The adapter reads two lists whatever happens; a route with no waypoint still has to
        // write the first one, or the destination is read off the wrong offset.
        assertArrayEquals(framework(emptyList()), handWritten(emptyList()))
    }

    @Test fun `an accented name survives the framing`() {
        val pois = listOf(NavigationHandoff.Poi(43.6, 3.9, "Alès Centre"))
        assertArrayEquals(framework(pois), handWritten(pois))
    }
}
