# HaloChat core uses kotlinx.serialization; keep the generated serializers of its wire models.
-keepclassmembers class id.co.haloai.halochat.** {
    *** Companion;
}
-keepclasseswithmembers class id.co.haloai.halochat.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class id.co.haloai.halochat.**$$serializer { *; }
