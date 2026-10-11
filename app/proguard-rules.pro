# Keep the JNI entry points: they are resolved by name at runtime, not by reference from
# Kotlin, so shrinking cannot see them.
-keepclasseswithmembernames,includedescriptorclasses class com.chuse.inference.LlamaBridge {
    native <methods>;
}
-keep class com.chuse.inference.** { *; }