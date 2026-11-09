# Reglas de R8. Compose y AndroidX traen sus propias reglas.
# android.os.SystemProperties se usa por reflexión en SystemInfo.
-dontwarn android.os.SystemProperties

# BRouter carga su modelo de manejo por nombre (---model:btools.router.KinematicModel en el perfil):
# sin esto R8 lo quitaría o le cambiaría el nombre.
-keep class btools.** { *; }
-dontwarn btools.**
