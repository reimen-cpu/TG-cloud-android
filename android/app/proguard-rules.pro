# Keep Retrofit/Moshi models
-keep class com.telegram.cloud.** { *; }

# WorkManager reflection
-keep class androidx.work.impl.background.systemjob.SystemJobService { *; }

# Glide generated modules/resolvers
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
-keep class * extends com.bumptech.glide.AppGlideModule { *; }
-keep class * extends com.bumptech.glide.module.LibraryGlideModule { *; }


