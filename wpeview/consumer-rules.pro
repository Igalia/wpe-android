# R8 rules for the apps that shrink their code. The native code of WPEView, and that of the GStreamer libraries it
# brings, finds the classes below and their members by name, so they must keep their names and cannot be removed. The
# classes in the signatures of those members keep their names too, since native code names them as well.

# WPEView, and GStreamer's library for its GStreamer class, register the native methods of each class at once when
# they load, and fail if one of them is missing, even one that the app never calls.
-keepclasseswithmembers,includedescriptorclasses class org.wpewebkit.**, org.freedesktop.gstreamer.GStreamer {
    native <methods>;
}

# The Java methods and fields that WPEView's native code uses are marked with @Keep. AndroidX Annotation brings rules
# for it too, but these do not depend on the app having them.
-keep,includedescriptorclasses @androidx.annotation.Keep class org.wpewebkit.** { *; }
-keepclasseswithmembers,includedescriptorclasses class org.wpewebkit.** {
    @androidx.annotation.Keep <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class org.wpewebkit.** {
    @androidx.annotation.Keep <fields>;
}

# GStreamer's Android media plugin creates these classes, sets their fields and calls their methods from native code.
-keep class org.freedesktop.gstreamer.androidmedia.** { *; }
