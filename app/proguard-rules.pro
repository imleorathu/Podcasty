# Podcasty ProGuard rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.podcasty.app.database.** { *; }
-keep class com.podcasty.app.domain.** { *; }

# NewPipeExtractor uses Rhino to evaluate YouTube's JavaScript player code.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

