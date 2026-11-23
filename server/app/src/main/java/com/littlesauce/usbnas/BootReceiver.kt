package com.littlesauce.usbnas

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 开机 / 插拔 U 盘 / 媒体挂载变化 → 自动拉起或刷新服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("BootReceiver", "收到 $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!NasService.isRunning) {
                    start(ctx, "开机自启")
                }
            }

            "android.hardware.usb.action.USB_DEVICE_ATTACHED" -> {
                // 等系统把卷挂上再启动
                Thread {
                    Thread.sleep(2500)
                    if (!NasService.isRunning) {
                        start(ctx, "U 盘已插入")
                    } else {
                        // 服务已在跑：只需让共享目录缓存失效，网页端立刻能看到 U 盘
                        NasService.invalidateRoots()
                        NasService.pushLog("检测到 U 盘插入，共享目录已刷新")
                    }
                }.start()
            }

            Intent.ACTION_MEDIA_MOUNTED -> {
                // 卷真正挂载完成，这时才有 /storage/XXXX-XXXX
                NasService.invalidateRoots()
                NasService.pushLog("存储卷已挂载，共享目录已刷新")
            }

            "android.hardware.usb.action.USB_DEVICE_DETACHED",
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT -> {
                NasService.invalidateRoots()
                NasService.pushLog("存储卷变化: $action")
            }
        }
    }

    private fun start(ctx: Context, why: String) {
        try {
            ContextCompat.startForegroundService(ctx,
                Intent(ctx, NasService::class.java).setAction(NasService.ACTION_START))
            NasService.pushLog("自动启动（$why）")
        } catch (e: Exception) {
            Log.w("BootReceiver", "启动失败", e)
        }
    }
}
