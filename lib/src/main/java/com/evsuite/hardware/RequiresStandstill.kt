package com.evsuite.hardware

/**
 * Marks an [EVHardware] write that changes road behaviour and is therefore permitted
 * **only at 0 km/h**.
 *
 * This is documentation with teeth: it does not itself enforce anything — enforcement is
 * [VehicleWriteGate], applied inside the low-level write primitives — but it names, at the
 * method, exactly which writes are speed-gated. A test in this module asserts the set of
 * annotated methods, so a setter cannot silently lose or gain gating without the change
 * being noticed.
 *
 * Comfort writes (seat/steering heating via CarHvacManager, media volume, screen
 * brightness, audio tuning) are deliberately NOT annotated: they do not alter how the car
 * behaves on the road, and the gate does not apply to them.
 *
 * SOURCE retention: the annotation is a compile-time contract read by the guard test via
 * the method list, not needed at runtime.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class RequiresStandstill
