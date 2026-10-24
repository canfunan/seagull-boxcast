package com.seagull.boxcast;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

/**
 * BoxCast ↔ 海鸥桌面（seagull-desktop 整入）桥接层。
 *
 * 定位：海鸥桌面 = BoxCast 的内置 HMI（被投屏内容）。
 *   投屏时前台是海鸥桌面 → MediaProjection 截它 → 编码 → 盒子；
 *   反控走 Window.injectInputEvent 直达海鸥当前 Activity（零权限，CarLife+ 同款）。
 */
public final class BoxCastBridge {

    private BoxCastBridge() {}

    /** 打开海鸥桌面（HomeActivity 已是 LAUNCHER，也可作默认桌面） */
    public static void openDesktop(Context ctx) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setClassName(ctx.getPackageName(), "com.seagull.carlauncher.HomeActivity");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    /** 打开画中画板（PipAnchorActivity：第三方 app VD 挂载/远端流槽位） */
    public static void openPipBoard(Context ctx) {
        Intent i = new Intent();
        i.setClassName(ctx.getPackageName(), "com.seagull.carlauncher.PipAnchorActivity");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    /**
     * 把反控目标窗口绑到当前前台 Activity。
     * 投屏期间海鸥的哪个 Activity 在前台，车机触摸就进哪个窗口。
     *
     * 注意 Kotlin→Java 互操作：object 走 INSTANCE，属性走 getter/setter。
     */
    public static void bindInjectorWindow(Activity activity) {
        com.seagull.boxcast.inject.TouchInjector inj =
                com.seagull.boxcast.inject.InjectorRegistry.INSTANCE.getInjector();
        if (inj != null) {
            inj.getWindowInjector().setActivity(activity);
        }
    }
}
