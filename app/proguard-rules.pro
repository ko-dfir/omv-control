# kotlinx.serialization ships its own consumer rules; keep our @Serializable models' serializers explicitly as well.
-keepclassmembers @kotlinx.serialization.Serializable class com.omvcompanion.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.omvcompanion.app.**$$serializer { *; }

# JSch resolves cryptographic algorithms from class names in its configuration.
-keep class com.jcraft.jsch.** { *; }

# WorkManager instantiates this internal database by reflection (verified on QA builds).
-keep class androidx.work.impl.WorkDatabase_Impl { public <init>(); }

# WorkManager also recreates these workers by class name after process restart.
-keep class com.omvcompanion.app.notifications.** extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.omvcompanion.app.widget.** extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Optional desktop agents, Kerberos, Unix sockets and SLF4J are not used by
# Android password/keyboard-interactive SSH authentication.
-dontwarn com.sun.jna.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
