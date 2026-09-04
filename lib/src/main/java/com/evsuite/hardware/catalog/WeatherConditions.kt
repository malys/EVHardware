package com.evsuite.hardware.catalog

import com.evsuite.hardware.R
import java.text.Normalizer

/**
 * The sky, as a short list a rule can be written against.
 *
 * The head unit's weather service answers a **phrase**, in the head unit's language: "Light
 * rain", "Rain showers", "Pluie faible". That phrase was once what a rule compared itself
 * against, and it made the condition unwritable — the driver had to guess the provider's
 * exact wording, in the exact language the car was set to, and a rule written on a French car
 * stopped matching the day the display language changed. Nobody can type a value they have
 * never seen.
 *
 * So the phrase is classified here, once, into one of seven states the picker can offer as a
 * list. The provider's wording stays the provider's business; the catalogue's vocabulary is
 * what a rule stores, which is why a rule keeps meaning "when it rains" whatever language the
 * car speaks afterwards.
 *
 * **Keywords, not a table of phrases.** The service is free to say "Heavy rain" today and
 * "Rain, heavy" tomorrow, and it says it in six languages; matching a fragment survives both,
 * where an exact list would need every phrase the provider owns. A phrase that matches
 * nothing is **not** classified — [classify] answers null and the condition stays
 * unavailable, which is the same answer a car that will not talk to its weather service
 * gives. Guessing "clear" from a phrase we did not recognise would be the one wrong answer:
 * it reads as good weather.
 */
object WeatherConditions {

    const val CLEAR  = 1
    const val CLOUDY = 2
    const val RAIN   = 3
    const val SNOW   = 4
    const val STORM  = 5
    const val FOG    = 6
    const val WIND   = 7

    val OPTIONS = listOf(
        EnumOption(CLEAR, R.string.weather_clear),
        EnumOption(CLOUDY, R.string.weather_cloudy),
        EnumOption(RAIN, R.string.weather_rain),
        EnumOption(SNOW, R.string.weather_snow),
        EnumOption(STORM, R.string.weather_storm),
        EnumOption(FOG, R.string.weather_fog),
        EnumOption(WIND, R.string.weather_wind)
    )

    /**
     * Fragments per state, in the languages the apps ship, **in the order they are tried**.
     *
     * The order is the whole design: real phrases name more than one thing ("thunderstorms
     * with rain", "snow showers", "Schneeschauer"), and the first match wins, so the states
     * are tried from the most specific weather to the least. A thunderstorm is a storm before
     * it is rain; falling snow is snow before it is a shower; the sky being clear is what is
     * left when nothing else matched.
     */
    private val KEYWORDS: List<Pair<Int, List<String>>> = listOf(
        STORM to listOf(
            "thunder", "storm", "squall",
            "orage", "tempete", "gewitter", "sturm", "tormenta", "temporale", "tempesta",
            "trovoada", "tempestade"
        ),
        SNOW to listOf(
            "snow", "sleet", "blizzard", "hail",
            "neige", "grele", "verglas", "schnee", "hagel", "nieve", "granizo", "aguanieve",
            "neve", "grandine"
        ),
        RAIN to listOf(
            "rain", "drizzle", "shower",
            "pluie", "bruine", "averse", "ondee", "regen", "niesel", "schauer", "lluvia",
            "llovizna", "chubasco", "piogg", "rovesc", "chuva", "chuvisco", "aguaceiro"
        ),
        FOG to listOf(
            "fog", "mist", "haze",
            "brouillard", "brume", "nebel", "dunst", "niebla", "neblina", "calima", "nebbia",
            "foschia", "nevoeiro"
        ),
        WIND to listOf(
            "wind", "gale", "breeze",
            "vent", "boen", "viento"
        ),
        CLOUDY to listOf(
            "cloud", "overcast",
            "nuage", "couvert", "wolk", "bedeckt", "nub", "cubierto", "nuvol", "coperto",
            "nuvem", "encoberto"
        ),
        CLEAR to listOf(
            "clear", "sun", "fair",
            "soleil", "clair", "degage", "sonn", "klar", "heiter", "sol", "despejado",
            "sereno", "limpo"
        )
    )

    /**
     * The state [phrase] describes, or null when nothing here recognises it.
     *
     * Accents are stripped before matching, so "Pluie éparse" and "Bewölkt" are read by the
     * same fragments that a head unit writing them without accents would produce.
     */
    fun classify(phrase: String): Int? {
        val normalised = normalise(phrase)
        if (normalised.isBlank()) return null
        return KEYWORDS.firstOrNull { (_, fragments) -> fragments.any { it in normalised } }?.first
    }

    private fun normalise(phrase: String): String =
        Normalizer.normalize(phrase.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
}
