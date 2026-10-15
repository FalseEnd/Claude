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
-keep interface tw.xiaoliangkou.app.data.GoTrueService { *; }
-keep interface tw.xiaoliangkou.app.data.RestService { *; }
-dontwarn org.osmdroid.**
# 與 Supabase 交換的資料模型與共用邏輯整包保留，避免 R8 改名後 JSON 對不上
-keep class tw.xiaoliangkou.app.data.** { *; }
-keep class tw.xiaoliangkou.app.core.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
