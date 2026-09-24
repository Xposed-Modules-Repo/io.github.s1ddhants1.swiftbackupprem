# Preserve line numbers and essential reflection annotations
-keepattributes LineNumberTable,*Annotation*,Signature
-renamesourcefileattribute SourceFile

# Repackage obfuscated classes to root to shrink DEX string pool
-repackageclasses ""

# Legacy Xposed API rules
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }
-keep public class * implements de.robv.android.xposed.IXposedHookLoadPackage {
    public <init>();
    public void handleLoadPackage(...);
}
-keep public class * implements de.robv.android.xposed.IXposedHookZygoteInit {
    public <init>();
    public void initZygote(...);
}
-adaptresourcefilecontents assets/xposed_init
-adaptresourcefilecontents assets/native_init

# AndroidX Preference (compileOnly provided by target app)
-dontwarn androidx.preference.**
-keep class io.github.s1ddhants1.swiftbackupprem.hook.InAppSettingsHook* { *; }
-keepclassmembers class io.github.s1ddhants1.swiftbackupprem.hook.InAppSettingsHook* { *; }
-keep class io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper* { *; }
-keepclassmembers class io.github.s1ddhants1.swiftbackupprem.util.LSPatchHelper* { *; }

# Keep DexKit bridge and schema/result classes used by native C++ (libdexkit.so) JNI reflection
-keep class org.luckypray.dexkit.DexKitBridge { *; }
-keep class org.luckypray.dexkit.schema.** { *; }
-keep class org.luckypray.dexkit.result.** { *; }
-keep class org.luckypray.dexkit.util.NativeReflect { *; }
-keepclasseswithmembers,includedescriptorclasses class org.luckypray.dexkit.** {
    native <methods>;
}

# Keep all native JNI method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}

# Optimization iterations
-optimizationpasses 5

# Broaden access modifiers to increase results during optimization
-allowaccessmodification
