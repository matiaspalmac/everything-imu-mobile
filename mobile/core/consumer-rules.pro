# =============================================================================
# ProGuard / R8 consumer rules for the everything-imu `core` module.
#
# STATUS: R8 / code shrinking is DISABLED for this module and every app that
#   consumes it. Release builds are intentionally UNSHRUNK and UNOBFUSCATED so
#   that shipped stack traces and bytecode stay readable during field debugging.
#   See `buildTypes { release { isMinifyEnabled = false } }` in the
#   core / app-mobile / app-wear build.gradle.kts files.
#
# WHY THIS FILE IS NOT EMPTY:
#   An empty consumer-rules.pro looks "done" but ships zero keep-rules. The
#   moment anyone flips `isMinifyEnabled = true`, R8 would strip the JNI
#   native-method bindings and any reflection/serialization surfaces, silently
#   breaking native sensor fusion at runtime with no compile-time warning.
#
# BEFORE ENABLING R8 / SHRINKING:
#   1. Set `isMinifyEnabled = true` on the release build type you are shrinking.
#   2. UNCOMMENT the keep-rules below. They are commented out on purpose while
#      minify is off, so that inactive rules never read as "already in force".
#   3. Assemble a release build and exercise native fusion on a device: confirm
#      VqfEngine.create() returns non-null and quaternion() streams, i.e.
#      libjni_android.so still binds to the VqfNative bridge below.
#   4. Re-verify DataStore reads/writes and any reflective surface still work,
#      then add keep-rules for anything R8 broke.
# =============================================================================


# --- JNI: native method bindings ---------------------------------------------
# libjni_android.so links to the Kotlin side by exact JNI symbol name
# (Java_cl_matiaspalma_everythingimu_core_fusion_VqfNative_native*). R8 must not
# rename the holder class or its native methods or the dynamic link fails and
# every fusion call throws UnsatisfiedLinkError at runtime.
#
# Keep every native method and its enclosing class name across the app:
#-keepclasseswithmembernames class * {
#    native <methods>;
#}
#
# Belt-and-suspenders: pin the exact JNI bridge object that declares the
# `external` methods and is reached via System.loadLibrary("jni_android").
#-keep class cl.matiaspalma.everythingimu.core.fusion.VqfNative {
#    *;
#}


# --- DataStore (Preferences) -------------------------------------------------
# `core` persists settings via androidx.datastore.preferences (Preferences
# DataStore, see AppPrefs). That path is key/value based and does NOT reflect
# over app model classes, so no app-owned keep-rule is required today.
# CalibrationData, MagCalibration and Vec3 are read/written field-by-field, not
# deserialized reflectively.
#
# IF a typed store is ever introduced (Proto DataStore, or a kotlinx.serialization
# @Serializable model with a generated Serializer<T>), keep that model and its
# serializer before shrinking, e.g.:
#-keepclassmembers class <your.serializable.Model> {
#    *;
#}
#-keep class <your.serializable.Model>$$serializer {
#    *;
#}


# --- Parcelable / kotlinx.serialization --------------------------------------
# No @Parcelize / Parcelable implementers and no @Serializable classes exist in
# this module tree at the time of writing, so there is nothing to keep yet. If
# any are added, preserve their generated CREATOR / serializer members before
# shrinking, e.g.:
#-keepclassmembers class * implements android.os.Parcelable {
#    public static final ** CREATOR;
#}
