# Room, Hilt and Compose ship their own consumer rules; nothing app-specific is needed yet.
# Keep line numbers so release stack traces remain readable when de-obfuscated locally.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Tesseract4Android ships no consumer rules, and its native code finds these classes, fields
# (e.g. mNativeData) and methods by name through JNI. Renaming or removing them breaks OCR at runtime.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# ONNX Runtime ships no consumer rules; its native code finds these classes and fields by name through JNI.
-keep class ai.onnxruntime.** { *; }
