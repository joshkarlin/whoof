package com.thelightphone.sdk

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import com.thelightphone.sdk.ui.LightQrCodeScanner

/**
 * Wrapper for the UI library's LightQrCodeScanner with brokered release permission handling and
 * direct Android permission handling for local debug builds.
 */
@Composable
fun LightQrCodeScanner(
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Scan QR Code",
) {
    if (BuildConfig.DEBUG) {
        val activity = LocalActivity.current
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = {},
        )
        LightQrCodeScanner(
            title = title,
            onScanned = onScanned,
            onBack = onBack,
            modifier = modifier,
            checkCameraPermission = {
                Result.success(
                    activity?.let {
                        ContextCompat.checkSelfPermission(it, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    } ?: false,
                )
            },
            launchCameraPermissionRequest = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
        )
    } else {
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.CAMERA)
        LightQrCodeScanner(
            title = title,
            onScanned = onScanned,
            onBack = onBack,
            modifier = modifier,
            checkCameraPermission = {
                checkPermission(Manifest.permission.CAMERA).asKotlinResult
                    .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
            },
            launchCameraPermissionRequest = {
                permissionLauncher?.launch()
            },
        )
    }
}
