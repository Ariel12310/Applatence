package com.arena.delayed_audio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var pendingPermissionResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "checkPermissions" -> result.success(permissionState())
                "requestPermissions" -> requestRuntimePermissions(result)
                "start" -> {
                    val latencyMs = call.argument<Int>("latencyMs") ?: 500
                    if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                        result.error("PERMISSION_DENIED", "Autorise le micro avant de démarrer.", null)
                        return@setMethodCallHandler
                    }
                    val intent = Intent(this, DelayedAudioService::class.java).apply {
                        action = DelayedAudioService.ACTION_START
                        putExtra(DelayedAudioService.EXTRA_LATENCY_MS, latencyMs)
                    }
                    ContextCompat.startForegroundService(this, intent)
                    result.success(null)
                }
                "stop" -> {
                    startService(Intent(this, DelayedAudioService::class.java).apply { action = DelayedAudioService.ACTION_STOP })
                    result.success(null)
                }
                "setLatency" -> {
                    val latencyMs = call.argument<Int>("latencyMs") ?: 500
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LATENCY, latencyMs).apply()
                    if (DelayedAudioService.status(this)["running"] == true) {
                        startService(Intent(this, DelayedAudioService::class.java).apply {
                            action = DelayedAudioService.ACTION_SET_LATENCY
                            putExtra(DelayedAudioService.EXTRA_LATENCY_MS, latencyMs)
                        })
                    }
                    result.success(null)
                }
                "getStatus" -> result.success(DelayedAudioService.status(this))
                "getRoutes" -> result.success(audioRoutes())
                "selectRoute" -> {
                    val id = call.argument<Int>("id")
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_ROUTE_ID, id ?: -1).apply()
                    if (DelayedAudioService.status(this)["running"] == true) {
                        startService(Intent(this, DelayedAudioService::class.java).apply {
                            action = DelayedAudioService.ACTION_SELECT_ROUTE
                            putExtra(DelayedAudioService.EXTRA_ROUTE_ID, id ?: -1)
                        })
                    }
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun requestRuntimePermissions(result: MethodChannel.Result) {
        if (pendingPermissionResult != null) {
            result.error("BUSY", "Une demande de permission est déjà ouverte.", null)
            return
        }
        val missing = requiredRuntimePermissions().filter { !hasPermission(it) }
        if (missing.isEmpty()) {
            result.success(permissionState())
            return
        }
        pendingPermissionResult = result
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            pendingPermissionResult?.success(permissionState())
            pendingPermissionResult = null
        }
    }

    private fun requiredRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun permissionState(): Map<String, Boolean> = mapOf(
        "microphone" to hasPermission(Manifest.permission.RECORD_AUDIO),
        "bluetooth" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasPermission(Manifest.permission.BLUETOOTH_CONNECT) else true,
        "notifications" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) hasPermission(Manifest.permission.POST_NOTIFICATIONS) else true,
    )

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun audioRoutes(): List<Map<String, Any>> {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val selected = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ROUTE_ID, -1)
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { device -> device.type != AudioDeviceInfo.TYPE_UNKNOWN }
            .map { device ->
                mapOf(
                    "id" to device.id,
                    "name" to (device.productName?.toString()?.ifBlank { null } ?: typeLabel(device.type)),
                    "type" to typeLabel(device.type),
                    "selected" to (selected == device.id),
                )
            }
            .ifEmpty {
                listOf(mapOf("id" to -1, "name" to "Sortie système", "type" to "default", "selected" to true))
            }
    }

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "haut-parleur"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "casque filaire"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "kit filaire"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "casque USB"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        else -> "audio"
    }

    companion object {
        private const val CHANNEL = "delayed_audio/control"
        private const val REQUEST_PERMISSIONS = 9107
        const val PREFS = "delayed_audio_prefs"
        const val KEY_LATENCY = "latency_ms"
        const val KEY_ROUTE_ID = "route_id"
    }
}
