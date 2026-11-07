# minifyEnabled сейчас false; правила подготовлены заранее, чтобы включить R8 после проверки.

# sherpa-onnx: имена JNI-функций привязаны к пакету
-keep class com.k2fsa.sherpa.onnx.** { *; }

# LiteRT-LM (Kotlin API + JNI)
-keep class com.google.ai.edge.litertlm.** { *; }

# Capacitor-плагины ищутся по аннотациям
-keep @com.getcapacitor.annotation.CapacitorPlugin public class * { *; }
-keepclassmembers class * { @com.getcapacitor.PluginMethod public <methods>; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class hackuna.matata.**$$serializer { *; }
-keepclassmembers class hackuna.matata.** { *** Companion; }
-keepclasseswithmembers class hackuna.matata.** { kotlinx.serialization.KSerializer serializer(...); }
