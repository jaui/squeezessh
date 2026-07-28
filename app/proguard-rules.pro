# SSHJ laedt Krypto-Implementierungen und Key-Provider reflektiv ueber Factories.
-keep class net.schmizz.sshj.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.schmizz.sshj.**

# BouncyCastle-Provider wird ueber Security.addProvider() reflektiv instanziiert.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**

# SSHJ referenziert optionale JDK-/GSS-APIs, die es auf Android nicht gibt.
-dontwarn java.security.**
-dontwarn javax.security.**
-dontwarn org.slf4j.**

# Termux-Terminal wird teilweise ueber JNI/Reflection angesprochen.
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
