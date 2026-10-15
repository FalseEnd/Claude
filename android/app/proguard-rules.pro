# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class tw.xiaoliangkou.app.**$$serializer { *; }
-keepclassmembers class tw.xiaoliangkou.app.** { *** Companion; }
-keepclasseswithmembers class tw.xiaoliangkou.app.** { kotlinx.serialization.KSerializer serializer(...); }
# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface tw.xiaoliangkou.app.data.Api { *; }
-dontwarn org.osmdroid.**
