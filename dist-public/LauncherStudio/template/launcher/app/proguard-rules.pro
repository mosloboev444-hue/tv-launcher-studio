# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# AmneziaWG backend: the Go library looks these classes up by name through JNI.
-keep class org.amnezia.** { *; }
-keep class com.gathin.tvhome.vpn.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
