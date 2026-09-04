package com.evsuite.hardware.telemetry

import kotlin.math.abs

/**
 * What the climbing costs, and how little of it comes back.
 *
 * CP-031 refused a grade source on 2026-09-01 and was right to: the vehicle publishes none, a
 * barometric reference is unproven, power-derived grade is circular, and sampled GPS altitude
 * describes the road *behind* the car. CP-047 changed the input rather than the argument — a
 * route carries an elevation profile of the road **ahead**, which is the only kind a forecast
 * can use. Toulouse to Alès crosses the Cévennes, and a forecast that does not know that is
 * wrong in the direction that strands people.
 *
 * This is physics, not a fit. Potential energy for a stated mass, a stated drivetrain
 * efficiency on the way up and a stated regeneration efficiency on the way down — every one of
 * them a declared constant with a band, none of them a parameter tuned against data this
 * project does not have. When there are trips over known profiles a fitted coefficient can
 * replace it; until then a fitted number would only be a guess wearing a measurement's clothes.
 *
 * **Regeneration is not free.** A descent returns charge, but less than the same climb spent,
 * because the round trip pays the drivetrain twice and pays the brakes wherever the descent is
 * steeper than the car's drag. That asymmetry is the whole point: a route over a col comes back
 * costing more than the flat one, never the same and never less.
 */
object RouteGrade {

    /**
     * The car and what is in it, in kilograms.
     *
     * MG4 Long Range kerb mass is about 1685 kg on the manufacturer's specification; a driver
     * and their luggage is the rest. A specification describes the model and not this car, so
     * this is a stated assumption carried in [RELATIVE_UNCERTAINTY] rather than a measurement —
     * see `AGENTS.md` on specification references.
     */
    const val MASS_KG = 1750.0

    const val GRAVITY_M_PER_S2 = 9.80665

    /** Battery to wheels going up: motor, inverter and reduction gear, all of them lossy. */
    const val DRIVETRAIN_EFFICIENCY = 0.85

    /**
     * Wheels back to battery going down, and deliberately pessimistic.
     *
     * The chain loses as much as the way up, and on top of that some of the descent is spent on
     * drag rather than recovered, and a steep one reaches the regeneration limit and goes to
     * the friction brakes. Overstating this is how a forecast promises charge that never
     * arrives, so it is understated instead.
     */
    const val REGENERATION_EFFICIENCY = 0.60

    /**
     * How wrong the three constants above may be, together.
     *
     * Mass is a specification plus a guess at the load, and neither efficiency was measured on
     * this car. Wide on purpose: a narrow band on numbers nobody measured is a false precision,
     * and this one feeds a stop decision.
     */
    const val RELATIVE_UNCERTAINTY = 0.40

    private const val JOULES_PER_KWH = 3_600_000.0

    /**
     * What the profile costs in points of charge, with its band.
     *
     * Negative on a route that descends more than it climbs, which is a real effect and is not
     * clamped away — a car crossing the Alps towards the sea does arrive with more than the flat
     * arithmetic says.
     */
    data class Cost(val percent: Double, val uncertaintyPercent: Double)

    /**
     * The grade term, or null when there is nothing to say.
     *
     * Null rather than zero, always: a silent zero grade term is a claim that the road is flat,
     * and this cannot tell a flat road from a missing profile.
     *
     * @param ascentMetres cumulative climb over the route, metres.
     * @param descentMetres cumulative descent over the route, metres.
     * @param capacity the pack the charge is a percentage of; without it there is no bridge
     *   between joules and state of charge.
     */
    fun of(
        ascentMetres: Double?,
        descentMetres: Double?,
        capacity: BatteryCapacityConfig?,
    ): Cost? {
        if (ascentMetres == null || descentMetres == null || capacity == null) return null
        if (!ascentMetres.isFinite() || !descentMetres.isFinite()) return null
        if (ascentMetres < 0.0 || descentMetres < 0.0) return null

        val potentialPerMetreKwh = MASS_KG * GRAVITY_M_PER_S2 / JOULES_PER_KWH
        val spentKwh = potentialPerMetreKwh * ascentMetres / DRIVETRAIN_EFFICIENCY
        val recoveredKwh = potentialPerMetreKwh * descentMetres * REGENERATION_EFFICIENCY

        val soc = capacity.socPercentForEnergy(spentKwh - recoveredKwh)
        val percent = soc.value ?: return null
        if (!percent.isFinite()) return null
        // The capacity's own band on top of the constants', added rather than combined in
        // quadrature: they are not independent errors and the wider answer is the safe one.
        val band = abs(percent) * RELATIVE_UNCERTAINTY + (soc.uncertainty ?: 0.0)
        return Cost(percent, band)
    }
}
