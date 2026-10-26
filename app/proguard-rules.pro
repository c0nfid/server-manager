# JSch создаёт реализации алгоритмов через Class.forName по именам из конфигурации.
-keep class com.jcraft.jsch.** { *; }
# BouncyCastle используется JSch для Ed25519, X25519, ChaCha20-Poly1305 и т.п.
-keep class org.bouncycastle.** { *; }

# Необязательные зависимости JSch/BouncyCastle, которых нет на Android.
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn javax.naming.**
-dontwarn java.net.UnixDomainSocketAddress
-dontwarn org.bouncycastle.**
-dontwarn com.jcraft.jsch.**
# Страховка: отсутствующие на Android классы JDK не должны ломать сборку релиза.
-ignorewarnings

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.srvmanager.app.**$$serializer { *; }
-keepclassmembers class com.srvmanager.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.srvmanager.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
