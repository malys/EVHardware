# Rules applied to any app that consumes MG4Hardware. The library is reflection-heavy on
# both sides — vehicle access reflects android.car, and the catalogue is read by name.

# MG4Hardware reflects android.car / SAIC SDK / ServiceManager targets by name.
-keep class com.mg4.hardware.MG4Hardware { *; }
-keep class android.car.** { *; }
-keep class com.saicmotor.** { *; }

# Serialized models (Gson) — fields and constructors read reflectively; keep generic
# signatures so TypeToken<List<DrivingProfile>> does not decay to LinkedTreeMap.
-keep class com.mg4.hardware.model.** { *; }
-keepclassmembers class com.mg4.hardware.model.** { <fields>; <init>(...); }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Catalogue enums: FirmwareSupport reads @SupportedOn off the enum fields by reflection,
# and enums are persisted by name — obfuscating either breaks the firmware filter and any
# stored rules.
-keep class com.mg4.hardware.catalog.** { *; }
-keep class com.mg4.hardware.FirmwareGen { *; }
-keep @interface com.mg4.hardware.SupportedOn
-keepclassmembers enum com.mg4.hardware.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
