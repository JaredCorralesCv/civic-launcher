# Reglas de R8. Compose y AndroidX traen sus propias reglas.
# android.os.SystemProperties se usa por reflexión en SystemInfo.
-dontwarn android.os.SystemProperties

# BRouter carga su modelo de manejo por nombre (---model:btools.router.KinematicModel en el perfil):
# sin esto R8 lo quitaría o le cambiaría el nombre.
-keep class btools.** { *; }
-dontwarn btools.**

# Kotlin revisa cada parámetro no nulo al entrar a cada función; en la versión final no hace falta
# (y en un Cortex-A7, con código interpretado, es una llamada de más en cada función).
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
}
