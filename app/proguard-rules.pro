# PDFForge Android / R8
# Keep app entry points discovered by the framework (also referenced in AndroidManifest).
-keep class com.xgamerstore.pdfforge.MainActivity { *; }
-keep class com.xgamerstore.pdfforge.PdfEditorActivity { *; }

# Android XML parser providers and PDFBox font resources can require resource names
# and reflective metadata at runtime.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class com.tom_roush.pdfbox.pdmodel.font.** { *; }
-keep class com.tom_roush.fontbox.ttf.** { *; }

# Do not blanket-disable warnings or keep all classes. If R8 reports missing
# dependencies in a release build, investigate the exact classes before adding rules.
