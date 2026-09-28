-injars build/guard-only.jar
-outjars build/guard-obf-v2.jar
-printmapping build/proguard-map-v2.txt
-libraryjars "C:/Users/renna/.gradle/caches/modules-2/files-2.1/net.fabricmc/fabric-loader/0.16.14/5778d47f536bf2c63ed2abc1a56f5a1c129e34a/fabric-loader-0.16.14.jar"
-libraryjars "C:/Users/renna/.gradle/caches/modules-2/files-2.1/com.github.oshi/oshi-core/6.6.5/e1099981fd15dc4236c4499d82aba1276fb43a9a/oshi-core-6.6.5.jar"
-libraryjars "C:/Users/renna/java/jdk-21/jmods/java.base.jmod"
-keep public class d1.G {
    public <init>();
    public void onInitializeClient();
}
-keep class com.dickclient.** { *; }
-dontwarn
-dontnote
-dontoptimize
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
