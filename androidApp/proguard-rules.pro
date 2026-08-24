# Dylan R8 — keep Media3 / SQLDelight / Ktor rules are pulled via consumer proguard
-keep class dylan.** { *; }
# Okio / Ktor keep line numbers for LogBuffer
-keepattributes SourceFile,LineNumberTable
# Coil / Compose no extra rules needed
