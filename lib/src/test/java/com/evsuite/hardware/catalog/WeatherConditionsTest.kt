package com.evsuite.hardware.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The classifier is what makes the weather condition writable, so what it must get right is
 * the phrase the head unit actually answers — in whichever of the shipped languages the car
 * is set to, and with the qualifiers the provider likes to add.
 */
class WeatherConditionsTest {

    @Test
    fun `a phrase is read whatever the language the head unit answers in`() {
        listOf(
            "Light rain" to WeatherConditions.RAIN,
            "Pluie faible" to WeatherConditions.RAIN,
            "Leichter Regen" to WeatherConditions.RAIN,
            "Lluvia débil" to WeatherConditions.RAIN,
            "Pioggia leggera" to WeatherConditions.RAIN,
            "Chuva fraca" to WeatherConditions.RAIN,
            "Sunny" to WeatherConditions.CLEAR,
            "Ensoleillé" to WeatherConditions.CLEAR,
            "Bewölkt" to WeatherConditions.CLOUDY,
            "Ciel couvert" to WeatherConditions.CLOUDY,
            "Brouillard" to WeatherConditions.FOG,
            "Nebbia" to WeatherConditions.FOG,
            "Vent fort" to WeatherConditions.WIND,
            "Neige" to WeatherConditions.SNOW
        ).forEach { (phrase, expected) ->
            assertEquals(phrase, expected, WeatherConditions.classify(phrase))
        }
    }

    @Test
    fun `a phrase naming two things is read as the one a rule is about`() {
        // Order is the design: the first match wins, so the specific weather has to be tried
        // before the shower or the sky it comes with.
        assertEquals(WeatherConditions.STORM, WeatherConditions.classify("Thunderstorm with rain"))
        assertEquals(WeatherConditions.SNOW, WeatherConditions.classify("Snow showers"))
        assertEquals(WeatherConditions.SNOW, WeatherConditions.classify("Schneeschauer"))
        assertEquals(WeatherConditions.RAIN, WeatherConditions.classify("Rain and cloudy"))
    }

    @Test
    fun `an unrecognised phrase is not classified`() {
        // Never CLEAR by default: an unknown phrase turned into fine weather is the one
        // wrong answer, because it reads as a positive statement about the sky.
        assertNull(WeatherConditions.classify(""))
        assertNull(WeatherConditions.classify("   "))
        assertNull(WeatherConditions.classify("Unavailable"))
    }

    @Test
    fun `every state the picker offers can come out of the classifier`() {
        val reachable = WeatherConditions.OPTIONS.map { it.value }.toSet()
        assertEquals(reachable, listOf(
            "Clear", "Cloudy", "Rain", "Snow", "Thunderstorm", "Fog", "Windy"
        ).mapNotNull { WeatherConditions.classify(it) }.toSet())
    }
}
