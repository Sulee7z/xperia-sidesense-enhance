package com.sidesense.enhance

import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field

/**
 * SideSenseEnhancer - Sony Side Sense 增强模块 (libxposed API / Vector 2.x)
 *
 * 功能:
 *  1. 横屏模式可以唤起侧感栏 (原版横屏下侧感条窗口位于屏幕外 + 条件门控, 无法使用)
 *  2. 只有触摸才显示侧感条, 打开/切换应用时不弹出 (原版 foreground_changed 会把
 *     透明度切到 ACTIVE, 侧感条以 80% 透明度弹出)
 *  3. 横屏侧感条不被强制回正/遮罩覆盖而消失或错位
 *  4. 横竖屏侧感栏菜单都出现在对应触摸位置
 *
 * 生产版本: 无任何日志输出, Hook 均为常量/轻量判断, 额外耗电可忽略。
 */
class Main : XposedModule() {

    companion object {
        private const val TARGET = "com.sonymobile.sidesenseapp"
        private const val PKG_COMMON = "com.sonymobile.sidesenseapp.common"
        private const val EVENT_FOREGROUND_CHANGED = "foreground_changed"
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET) return
        try {
            installHooks(param.classLoader)
        } catch (_: Throwable) {
            // 静默: Hook 安装失败不影响应用本身
        }
    }

    private fun installHooks(cl: ClassLoader) {
        val displayUtil = cl.loadClass("$PKG_COMMON.util.DisplayUtil")

        // 真实横屏检测: 基于 Display 旋转角 (ROTATION_90=1 / ROTATION_270=3),
        // 不依赖应用资源的 configuration, 也不依赖被 Hook 的 isPortrait。
        fun isLandscape(ctx: Context?): Boolean {
            if (ctx == null) return false
            return try {
                val rot = (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay.rotation
                rot == 1 || rot == 3
            } catch (_: Throwable) {
                false
            }
        }

        // ---------- 0. DisplayUtil.isPortrait: 强制竖屏 ----------
        // 让应用认为设备始终处于竖屏, 所有横屏拦截点失效, 横屏即可唤起。
        // isPortrait(int) 也强制 true: 让菜单位置计算在横屏也使用拇指 Y,
        // 配合 thumbY 百分比归一化, 横竖屏菜单都出现在对应触摸位置。
        hook(displayUtil.getMethod("isPortrait", Context::class.java)).intercept { true }
        hook(displayUtil.getMethod("isPortrait", Int::class.javaPrimitiveType)).intercept { true }

        // ---------- 1. ConditionChecker.canShowDisplayTouchView: 横屏放宽 ----------
        // 原版横屏下受 isNavigationHide/keyguard 门控, 侧感条无法 add/open。
        val conditionChecker = cl.loadClass("$PKG_COMMON.util.ConditionChecker")
        val canShowTouchView = conditionChecker.getDeclaredMethod("canShowTouchView").apply { isAccessible = true }
        val isMuteDialog = conditionChecker.getDeclaredMethod("isSideSenseMuteDialog").apply { isAccessible = true }
        val isTalkBack = conditionChecker.getDeclaredMethod("isTalkBackMode").apply { isAccessible = true }
        val isDisplayTouchEnabled = conditionChecker.getDeclaredMethod("isDisplayTouchEnabled").apply { isAccessible = true }
        hook(conditionChecker.getMethod("canShowDisplayTouchView")).intercept { chain ->
            val self = chain.thisObject ?: return@intercept chain.proceed()
            val ctx = contextOf(self)
            if (isLandscape(ctx)) {
                return@intercept !(isTalkBack.invoke(self) as Boolean)
                        && (isDisplayTouchEnabled.invoke(self) as Boolean)
                        && !(isMuteDialog.invoke(self) as Boolean)
                        && (canShowTouchView.invoke(self) as Boolean)
            }
            chain.proceed()
        }

        // ---------- 2. SidebarViewController.getPosition: 横屏按比例映射 ----------
        // 原版 position 存的是竖屏坐标, 横屏下会落到屏幕外。按比例映射:
        // 竖屏顶部 -> 横屏顶部, 竖屏底部 -> 横屏底部, 任意分辨率自适应。
        val sidebarVc = cl.loadClass("$PKG_COMMON.sensing.displaytouch.sidebar.SidebarViewController")
        hook(sidebarVc.getMethod("getPosition")).intercept { chain ->
            val self = chain.thisObject ?: return@intercept chain.proceed()
            val ctx = contextOf(self)
            if (isLandscape(ctx) && ctx != null) {
                val portPos = chain.proceed() as Int
                val bounds = (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .maximumWindowMetrics.bounds
                val landH = minOf(bounds.width(), bounds.height())
                val portH = maxOf(bounds.width(), bounds.height())
                return@intercept (portPos.toLong() * landH / portH).toInt()
            }
            chain.proceed()
        }

        // ---------- 3. 横屏禁止写回位置偏好 ----------
        // 横屏拖动/写回会把横屏坐标污染竖屏位置偏好。
        hook(sidebarVc.getMethod("movePosition", Int::class.javaPrimitiveType)).intercept { chain ->
            if (isLandscape(contextOf(chain.thisObject))) return@intercept null
            chain.proceed()
        }
        val dtm = cl.loadClass("$PKG_COMMON.sensing.displaytouch.DisplayTouchManager")
        val viewParamsType = cl.loadClass("$PKG_COMMON.sensing.displaytouch.DisplayTouchManager\$ViewParamsType")
        hook(dtm.getDeclaredMethod("storeViewPosition", viewParamsType)).intercept { chain ->
            if (isLandscape(contextOf(chain.thisObject))) return@intercept null
            chain.proceed()
        }

        // ---------- 5. 拦截 foreground_changed (只有触摸才显示) ----------
        // 打开/切换应用时系统发 foreground_changed, 原版逻辑把透明度切到 ACTIVE(0.8)
        // 弹出侧感条。拦截后透明度保持 IDLE(0), 侧感条只在触摸时通过反馈动画显示。
        val eventsCb = cl.loadClass("$PKG_COMMON.sensing.displaytouch.sidebar.SidebarViewController\$3")
        hook(eventsCb.getDeclaredMethod("onEvent", String::class.java)).intercept { chain ->
            if (chain.getArg(0) == EVENT_FOREGROUND_CHANGED) return@intercept null
            chain.proceed()
        }

        // ---------- 7/8. 横屏禁止 GONE / 移除窗口 ----------
        // status=7 会把侧感条视图置 GONE(失去 surface/触摸区域), status=3 会移除窗口,
        // 都会导致侧感条"消失"。横屏时禁止。
        hook(sidebarVc.getDeclaredMethod("goneOverlayView")).intercept { chain ->
            if (isLandscape(contextOf(chain.thisObject))) return@intercept null
            chain.proceed()
        }
        hook(sidebarVc.getDeclaredMethod("closeOverlayView")).intercept { chain ->
            if (isLandscape(contextOf(chain.thisObject))) return@intercept null
            chain.proceed()
        }

        // ---------- 13. changeSidebarView: 横屏状态校正 ----------
        // 竖屏时会把侧感条视图置 GONE(自动隐藏), 转横屏后状态残留 -> 不可触摸。
        // 横屏下: 禁止 close(3)/gone(7); 任何 1..6 状态更新后若视图为 GONE 则恢复
        // VISIBLE (alpha 仍为 0, 保持"触摸才显示")。
        hook(sidebarVc.getDeclaredMethod("changeSidebarView", Int::class.javaPrimitiveType)).intercept { chain ->
            val self = chain.thisObject ?: return@intercept chain.proceed()
            val ctx = contextOf(self)
            val status = chain.getArg(0) as Int
            if (isLandscape(ctx)) {
                if (status == 3 || status == 7) return@intercept null
                val result = chain.proceed()
                if (status in 1..6) {
                    try {
                        val v = fieldOf(self, "mSidebarViewLeft") as? android.view.View
                        if (v != null && v.visibility == android.view.View.GONE) {
                            v.visibility = android.view.View.VISIBLE
                        }
                    } catch (_: Throwable) {
                    }
                }
                return@intercept result
            }
            chain.proceed()
        }

        // ---------- 12. 横屏禁用强制过渡(强行回正) ----------
        // 强制过渡(status 8-13)会添加全屏 TransitionView 遮罩(盖住侧感条)并重算
        // 写回位置(错位)。横屏跳过。
        val tvc = cl.loadClass("$PKG_COMMON.sensing.displaytouch.transition.TransitionViewController")
        val dtData = cl.loadClass("$PKG_COMMON.sensing.displaytouch.DisplayAndFloatingData")
        hook(tvc.getMethod("changeOverlayView", Int::class.javaPrimitiveType, dtData)).intercept { chain ->
            val status = chain.getArg(0) as Int
            if (isLandscape(contextOf(chain.thisObject)) && status in 8..13) return@intercept null
            chain.proceed()
        }
        hook(tvc.getMethod("changeOverlayView", Int::class.javaPrimitiveType)).intercept { chain ->
            val status = chain.getArg(0) as Int
            if (isLandscape(contextOf(chain.thisObject)) && status in 8..13) return@intercept null
            chain.proceed()
        }

        // ---------- 14. thumbY 百分比归一化 (横竖屏菜单位置各自正确) ----------
        // 横屏双击会把横屏坐标写进 thumb_position_y, 竖屏打开菜单用它计算会错位
        // (反之亦然)。写入时归一化为"占屏幕高度百分比", 读取时按当前方向高度换算。
        val prefsCls = cl.loadClass("$PKG_COMMON.preferences.Preferences")
        fun screenHeight(ctx: Context): Int {
            return (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .maximumWindowMetrics.bounds.height()
        }
        hook(prefsCls.getMethod("putThumPositionY", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
            .intercept { chain ->
                val ctx = contextOf(chain.thisObject)
                if (ctx != null) {
                    val y = chain.getArg(1) as Int
                    val h = screenHeight(ctx)
                    if (h > 0) {
                        val percent = (y.toLong() * 100 / h).toInt().coerceIn(0, 100)
                        return@intercept chain.proceed(arrayOf(chain.getArg(0), percent))
                    }
                }
                chain.proceed()
            }
        hook(prefsCls.getMethod("getThumPositionY", Int::class.javaPrimitiveType))
            .intercept { chain ->
                val ctx = contextOf(chain.thisObject)
                if (ctx != null) {
                    val percent = chain.proceed() as Int
                    val h = screenHeight(ctx)
                    return@intercept (percent.toLong() * h / 100).toInt()
                }
                chain.proceed()
            }

        // ---------- 6. Preferences.isOobeDone: 视为 OOBE 已完成 ----------
        // SideSenseMenuAction.isExecutable 横屏时要求 isOobeDone()==true, 否则弹
        // "横屏不可用" toast 拒绝打开。强制 true 使横屏可唤起。
        hook(prefsCls.getMethod("isOobeDone")).intercept { true }
    }

    /** 从实例对象中反射读取 mContext (字段可能在父类中声明) */
    private fun contextOf(obj: Any?): Context? {
        if (obj == null) return null
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f: Field = c.getDeclaredField("mContext").apply { isAccessible = true }
                return f.get(obj) as? Context
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (_: IllegalAccessException) {
                return null
            }
        }
        return null
    }

    /** 从实例对象中反射读取任意字段 (字段可能在父类中声明) */
    private fun fieldOf(obj: Any?, name: String): Any? {
        if (obj == null) return null
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f: Field = c.getDeclaredField(name).apply { isAccessible = true }
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (_: IllegalAccessException) {
                return null
            }
        }
        return null
    }
}
