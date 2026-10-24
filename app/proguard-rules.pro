# Release 混淆规则（R8）。
#
# 原则：**只保留确实用了反射的地方**，其余全交给 R8。
# 本项目 debug 包 41MB（dex 38MB），开 R8 的预期收益主要来自
# Compose / Media3 / Coil 这些自带 consumer rules 的库被正确裁剪。

# ---------------------------------------------------------------- 可读性
# 保留行号与源文件名，让 release 包的崩溃栈依然可读（不保留则全部显示 Unknown Source）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Kotlin 元数据：data class 的 copy()/componentN() 是编译器生成的，不需要反射，
# 但注解和泛型签名被某些库读取
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# ---------------------------------------------------------------- 序列化 / 反射边界
# org.json 是 Android 框架自带的，按类名反射构造
-dontwarn org.json.**

# ---------------------------------------------------------------- 压缩库
# zip4j：枚举（AesKeyStrength / EncryptionMethod / CompressionMethod）会被
# 反序列化路径按名字查找；加密实现通过 JCA 获取，但参数模型类要保持字段名
-keep class net.lingala.zip4j.model.enums.** { *; }
-keep class net.lingala.zip4j.model.** { *; }
-dontwarn net.lingala.zip4j.**

# commons-compress：7z 的编解码器通过 Class.forName / newInstance 加载
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**
-keep class org.apache.commons.compress.archivers.sevenz.** { *; }
-keep class org.apache.commons.compress.compressors.** { *; }
-dontwarn org.apache.commons.compress.**

# ---------------------------------------------------------------- 图片 / 播放器
# Coil 与 Media3 自带 consumer ProGuard rules，这里只压掉"可选依赖缺失"的警告
-dontwarn coil.**
-dontwarn androidx.media3.**
# ExoPlayer 的 DefaultMediaSourceFactory 走反射注册 extractor
-keep class androidx.media3.exoplayer.mediacodec.** { *; }

# ---------------------------------------------------------------- Compose / Wear
-dontwarn androidx.compose.**
-dontwarn androidx.wear.compose.**

# ---------------------------------------------------------------- 协程
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---------------------------------------------------------------- 本项目
# 页面栈 Screen 是 sealed interface + data class，靠 when 分发，不反射。
# 但 data class 的 toString() 在日志里用到，保留字段名便于排查
-keep class com.tengwear.tengfilemanager.presentation.Screen { *; }
-keep class com.tengwear.tengfilemanager.presentation.Screen$* { *; }
