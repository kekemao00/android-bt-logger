# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# 蒲公英：一般不需要添加，如果需要，则添加以下代码
#-keep class com.pgyer.pgyersdk.** { *; }
#-keep class com.pgyer.pgyersdk.**$* { *; }


# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

#  RxDownload
# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-dontnote retrofit2.Platform
# Platform used when running on Java 8 VMs. Will not be used at runtime.
-dontwarn retrofit2.Platform$Java8
# Retain generic type information for use by reflection by converters and adapters.
-keepattributes Signature
# Retain declared checked exceptions for use by a Proxy instance.
-keepattributes Exceptions

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

-dontwarn com.squareup.okhttp.**


# retrofit2 需要这些，不然下载时会报错，但是rxdownload没有给出这些混淆规则
-keepattributes Signature

# 保持Retrofit2接口的类和方法不被混淆
-keep interface retrofit2.** {
    *;
}

# 保持Retrofit2的转换器不被混淆
-keep class retrofit2.** {
    *;
}

# 保持OkHttp库的类不被混淆
-keep class okhttp3.** {
    *;
}

# 保持Gson库的类不被混淆
-keep class com.google.gson.** {
    *;
}

# 保持RxJava库的类不被混淆
-keep class io.reactivex.** {
    *;
}

# 保持所有Retrofit2生成的代理类不被混淆
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# 保持Retrofit2注解不被混淆
-keepattributes *Annotation*

# retrofit2 完
