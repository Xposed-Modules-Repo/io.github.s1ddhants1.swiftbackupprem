# Preserve line numbers and source file attributes for debugging stack traces
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

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

# Keep DexKit bridge classes used by native C++ (libdexkit.so) JNI reflection
-keep class org.luckypray.dexkit.** { *; }
-keepclassmembers class org.luckypray.dexkit.** { *; }

# Keep all native JNI method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}

# Optimization iterations
-optimizationpasses 5

# Broaden access modifiers to increase results during optimization
-allowaccessmodification
