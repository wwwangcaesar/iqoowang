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

# 高德定位 SDK（当前 minifyEnabled=false 用不上这几行，但先加上，
# 免得以后哪天开混淆构建 release 包时，定位功能因为类被裁剪/改名而莫名其妙失效）
-keep class com.amap.api.location.**{*;}
-keep class com.amap.api.fence.**{*;}
-keep class com.loc.**{*;}
-keep class com.autonavi.aps.amapapi.model.**{*;}

# 高德地图/搜索 SDK（同样先加上备用，当前 minifyEnabled=false 暂时用不到）
-keep class com.amap.api.maps.**{*;}
-keep class com.amap.api.services.**{*;}
-keep class com.autonavi.**{*;}
-keep class com.amap.api.trace.**{*;}