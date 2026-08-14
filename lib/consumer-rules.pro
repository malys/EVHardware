# Rules applied to any app that consumes EVHardware. The library is reflection-heavy on
# both sides — vehicle access reflects android.car, and the catalogue is read by name.

# EVHardware reflects android.car / SAIC SDK / ServiceManager targets by name.
-keep class com.evsuite.hardware.EVHardware { *; }
-keep class android.car.** { *; }
-keep class com.saicmotor.** { *; }

# Serialized models (Gson) — fields and constructors read reflectively; keep generic
# signatures so TypeToken<List<DrivingProfile>> does not decay to LinkedTreeMap.
-keep class com.evsuite.hardware.model.** { *; }
-keepclassmembers class com.evsuite.hardware.model.** { <fields>; <init>(...); }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Catalogue enums: FirmwareSupport reads @SupportedOn off the enum fields by reflection,
# and enums are persisted by name — obfuscating either breaks the firmware filter and any
# stored rules.
-keep class com.evsuite.hardware.catalog.** { *; }
-keep class com.evsuite.hardware.FirmwareGen { *; }
-keep @interface com.evsuite.hardware.SupportedOn
-keepclassmembers enum com.evsuite.hardware.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
