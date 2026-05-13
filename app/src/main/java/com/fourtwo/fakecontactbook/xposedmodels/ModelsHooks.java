package com.fourtwo.fakecontactbook.xposedmodels;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;

import java.util.Objects;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ModelsHooks {
    private static Context applicationContext = null;
    private static String packageName;
    public static void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        packageName = loadPackageParam.packageName;

        XposedHelpers.findAndHookMethod(ContextWrapper.class, "attachBaseContext", Context.class, new XC_MethodHook() {
            @SuppressLint("WrongConstant")
            @Override
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                if (applicationContext == null) {
                    applicationContext = (Context) param.args[0];
                    HookStart();
                    XposedBridge.log(packageName + ": xposed挂载成功 attachBaseContext");
                }
            }
        });

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                if (applicationContext == null) {
                    applicationContext = (Context) param.args[0];
                    HookStart();
                    XposedBridge.log(packageName + ": xposed挂载成功 attach");
                }
            }
        });
    }

    public static void HookStart(){
        if (Objects.equals(packageName, "com.tencent.mobileqq")){
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mobileqq.install(applicationContext);
            }
        }
    }

}
