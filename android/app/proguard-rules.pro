# Keep Retrofit/Moshi models
-keep class com.telegram.cloud.** { *; }

# WorkManager reflection
-keep class androidx.work.impl.background.systemjob.SystemJobService { *; }

# Glide generated modules/resolvers
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
-keep class * extends com.bumptech.glide.AppGlideModule { *; }
-keep class * extends com.bumptech.glide.module.LibraryGlideModule { *; }

# Gson TypeToken - CRITICAL for R8/ProGuard
# Keep generic signatures for Gson TypeToken usage
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep TypeToken and its subclasses
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep Gson TypeToken anonymous classes
-keep class com.telegram.cloud.data.sync.SyncEngine$* { *; }

# Keep data classes used in JSON serialization/deserialization
-keep class com.telegram.cloud.data.sync.SyncEngine$SyncChainNode { *; }
-keep class com.telegram.cloud.data.sync.SyncEngine$SyncIndexEntry { *; }
-keep class com.telegram.cloud.data.local.CloudFileEntity { *; }
-keep class com.telegram.cloud.data.sync.SyncLogEntity { *; }
-keep class com.telegram.cloud.data.sync.SyncMetadataEntity { *; }

