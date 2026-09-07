# Project-specific ProGuard/R8 rules for TaskIntercept.
#
# Why this file is intentionally empty:
#
# Room (KSP): generates TaskDao_Impl / AppDatabase_Impl at compile time via
# KSP annotation processing. These generated classes directly reference all
# @Entity fields and the Priority enum by name — R8 sees them as normal
# static references and keeps them without a -keep rule. No runtime
# Class.forName or field-name-string lookups anywhere in this project.
#
# Android components (TaskInterceptAccessibilityService, InterceptForeground-
# Service, BootReceiver, MainActivity): kept automatically by AGP's built-in
# rules for manifest-declared components.
#
# DataStore Preferences, Jetpack Compose, Coroutines: consumer ProGuard rules
# are shipped in each library's AAR and applied automatically.
#
# If a future dependency requires an explicit -keep, add it here with a
# comment explaining which class/feature needs it and why.
