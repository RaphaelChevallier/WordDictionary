# Kotlin serialization generates the model serializers used by the offline packs.
-keepattributes Signature,InnerClasses,EnclosingMethod

# JNI entrypoints are resolved by their original class and method names.
-keep class com.github.luben.zstd.** { *; }
