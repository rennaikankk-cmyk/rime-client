# dick-guard obfuscation — d1.* aggressively renamed (kept in package for
# package-private cross access), author classes kept byte-compatible.
-injars build/merged.jar
-outjars dist/dickclient-1.10.4.jar
-printmapping build/proguard-map.txt

-libraryjars "C:/Users/renna/.gradle/caches/modules-2/files-2.1/net.fabricmc/fabric-loader/0.16.14/5778d47f536bf2c63ed2abc1a56f5a1c129e34a/fabric-loader-0.16.14.jar"
-libraryjars "C:/Users/renna/.gradle/caches/modules-2/files-2.1/com.github.oshi/oshi-core/6.6.5/e1099981fd15dc4236c4499d82aba1276fb43a9a/oshi-core-6.6.5.jar"
-libraryjars "C:/Users/renna/java/jdk-21/lib/jmods/java.base.jmod"

-keep public class d1.G {
    public <init>();
    public void onInitializeClient();
}

# author classes: never shrink/obfuscate (run byte-identical via reflection)
-keep class com.dickclient.** { *; }

# runtime-provided libs (MC ships oshi; fabric present at runtime)
-dontwarn com.dickclient.**
-dontwarn net.minecraft.**
-dontwarn oshi.**

-dontnote
-optimizationpasses 3
-keepattributes InnerClasses,Signature
