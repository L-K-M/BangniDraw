# R8 rules for 帮你Draw.
#
# House warning (AGENTS.md): "works in debug, breaks in release" is almost
# always a missing keep rule for a new reflection/serialization entry point —
# check here first.

# kotlinx.serialization — keep generated serializers for our own classes
# (project.json, brush presets, history entry headers, navigation routes).
-keepclassmembers class ch.lkmc.bangnidraw.** {
    *** Companion;
}
-keepclasseswithmembers class ch.lkmc.bangnidraw.** {
    kotlinx.serialization.KSerializer serializer(...);
}
