# Preserve line numbers and source file attributes for debugging stack traces
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Keep all module classes, fields, and methods from R8 obfuscation/shrinking
-keep class io.github.s1ddhants1.swiftbackupprem.** { *; }
-keepclassmembers class io.github.s1ddhants1.swiftbackupprem.** { *; }

# LibXposed Modern API rules
-dontwarn io.github.libxposed.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-adaptresourcefilecontents META-INF/xposed/native_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

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
