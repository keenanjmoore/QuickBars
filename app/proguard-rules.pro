-keepattributes *Annotation*, Signature

-keep class * extends com.google.gson.TypeAdapter { *; }
-keep class * implements com.google.gson.JsonSerializer { *; }
-keep class * implements com.google.gson.JsonDeserializer { *; }

-keep class dev.trooped.tvquickbars.data.** { *; }
-keep class dev.trooped.tvquickbars.ha.ws.** { *; }
-keepclassmembers class dev.trooped.tvquickbars.camera.CameraRequest { *; }
-keepclassmembers class dev.trooped.tvquickbars.camera.CameraPipSpec { *; }
-keepclassmembers class dev.trooped.tvquickbars.camera.PipAction { *; }
-keepclassmembers class dev.trooped.tvquickbars.camera.RtspProfile { *; }
-keepclassmembers class dev.trooped.tvquickbars.notification.NotificationSpec { *; }
-keepclassmembers class dev.trooped.tvquickbars.notification.NotificationAction { *; }

# Keep Media3 RTSP reflection targets
-keep class androidx.media3.exoplayer.rtsp.** { *; }

# Strip verbose/debug logging calls in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int d(...);
    public static int v(...);
}
