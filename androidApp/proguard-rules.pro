# Dylan R8 — consumer rules from Media3/SQLDelight/Ktor already keep what they need.
# Keep only DB schema (reflectively referenced) and diag sinks.
-keep class dylan.db.** { *; }
-keep class dylan.diag.** { *; }
-keepattributes SourceFile,LineNumberTable
