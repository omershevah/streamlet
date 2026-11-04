# libtorrent4j is a JNI binding: native code looks up these classes and methods by name.
-keep class org.libtorrent4j.swig.** { *; }
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**
