# R8 is enabled for release builds. material-icons-extended alone is ~35 MB of
# generated icon classes; without shrinking the APK is unusable for sideloading.

-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations, EnclosingMethod

# ---- kotlinx.serialization -------------------------------------------------
# The library ships its own consumer rules, but these pin our own models
# explicitly so a future dependency bump cannot silently break JSON parsing.
-keep @kotlinx.serialization.Serializable class dev.danny.sundial.** { *; }

-keepclassmembers class dev.danny.sundial.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.danny.sundial.**$$serializer { *; }
-keepclasseswithmembers class dev.danny.sundial.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- WorkManager -----------------------------------------------------------
# Workers are instantiated reflectively from their class name.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---- Components named in the manifest --------------------------------------
-keep class dev.danny.sundial.SundialApp { *; }
-keep class dev.danny.sundial.MainActivity { *; }
-keep class dev.danny.sundial.reminders.ReminderReceiver { *; }
-keep class dev.danny.sundial.reminders.BootReceiver { *; }

# ---- OkHttp / Okio ---------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# ---- Misc ------------------------------------------------------------------
-dontwarn kotlinx.serialization.**
-dontwarn java.lang.invoke.**
