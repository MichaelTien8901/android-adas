package com.adasedge.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permission helpers. The app needs CAMERA (to see the road) and
 * fine location (GPS speed). POST_NOTIFICATIONS is required on Android 13+ for
 * the foreground-service notification.
 */
object Permissions {

    val required: Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun hasCamera(ctx: Context): Boolean = granted(ctx, Manifest.permission.CAMERA)

    fun hasLocation(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun allGranted(ctx: Context): Boolean = required.all { granted(ctx, it) }

    private fun granted(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
}
