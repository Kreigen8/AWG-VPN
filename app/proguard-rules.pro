# The published native tunnel library calls this exact Java class through JNI.
-keep class org.amnezia.awg.GoBackend { *; }
-keepclasseswithmembernames class * { native <methods>; }
