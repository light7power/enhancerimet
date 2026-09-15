package me.firesun.dingtalk.enhancement.modern;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.PathClassLoader;

/** Generates and validates a small runtime profile for the installed Rimet APK. */
final class LocalConfigManager {
    static final String TARGET = "com.alibaba.android.rimet";
    private static final String PREFS = "config";
    private static final String PREFIX = "local_profile_";

    private LocalConfigManager() {}

    static Result generate(Context context, boolean clearFirst) {
        try {
            if (App.service == null) return Result.failure("libxposed 配置服务不可用");
            SharedPreferences prefs = App.service.getRemotePreferences(PREFS);
            if (clearFirst) prefs.edit().remove(PREFIX + "status").remove(PREFIX + "error").apply();

            PackageInfo info = context.getPackageManager().getPackageInfo(TARGET, 0);
            String apk = info.applicationInfo.sourceDir;
            PathClassLoader loader = new PathClassLoader(apk, ClassLoader.getSystemClassLoader());
            List<String> passed = new ArrayList<>();
            List<String> failed = new ArrayList<>();

            Class<?> messageImpl = require(loader, "com.alibaba.wukong.im.message.MessageImpl", failed);
            Class<?> callback = require(loader, "com.alibaba.wukong.Callback", failed);
            Class<?> messageCache = require(loader, "com.alibaba.wukong.im.message.MessageCache", failed);
            Class<?> conversation = require(loader, "com.alibaba.wukong.im.conversation.ConversationImpl", failed);
            Class<?> contentValues = Class.forName("android.content.ContentValues");

            if (messageImpl != null && callback != null) {
                check(loader, "nii", "I", new Class<?>[]{java.util.Map.class, List.class, List.class, callback}, passed, failed);
                check(loader, "nii", "K", new Class<?>[]{String.class, long.class, callback}, passed, failed);
            }
            if (messageCache != null && messageImpl != null) {
                check(messageCache, "M0", new Class<?>[]{String.class, messageImpl, Integer.class}, passed, failed);
                check(messageCache, "f0", new Class<?>[]{String.class, List.class}, passed, failed);
            }
            if (conversation != null) {
                check(loader, "ofi", "C", new Class<?>[]{conversation, List.class}, passed, failed);
                check(loader, "ofi", "T", new Class<?>[]{String.class, String.class, List.class}, passed, failed);
                check(loader, "ofi", "f", new Class<?>[]{String.class, List.class, contentValues}, passed, failed);
            }
            check(loader, "qfi", "W", new Class<?>[]{}, passed, failed);

            boolean coreOk = failed.isEmpty();
            SharedPreferences.Editor edit = prefs.edit()
                    .putString(PREFIX + "package", TARGET)
                    .putString(PREFIX + "version_name", info.versionName == null ? "" : info.versionName)
                    .putLong(PREFIX + "version_code", info.versionCode)
                    .putString(PREFIX + "passed", join(passed))
                    .putString(PREFIX + "failed", join(failed))
                    .putLong(PREFIX + "generated_at", System.currentTimeMillis())
                    .putString(PREFIX + "status", coreOk ? "ready" : "failed");
            if (coreOk) edit.remove(PREFIX + "error");
            else edit.putString(PREFIX + "error", join(failed));
            edit.apply();
            return new Result(coreOk, info.versionName, info.versionCode, passed.size(), failed);
        } catch (PackageManager.NameNotFoundException e) {
            return Result.failure("未找到已安装的钉钉");
        } catch (Throwable e) {
            return Result.failure(e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
    }

    static void clear(Context context) {
        if (App.service != null) {
            App.service.getRemotePreferences(PREFS).edit()
                    .remove(PREFIX + "status")
                    .remove(PREFIX + "error")
                    .remove(PREFIX + "passed")
                    .remove(PREFIX + "failed")
                    .remove(PREFIX + "generated_at")
                    .apply();
        }
    }

    private static Class<?> require(ClassLoader loader, String name, List<String> failed) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable e) {
            failed.add(name + " 缺失");
            return null;
        }
    }

    private static void check(ClassLoader loader, String shortName, String name,
                              Class<?>[] args, List<String> passed, List<String> failed) {
        try {
            check(Class.forName(shortName, false, loader), name, args, passed, failed);
        } catch (Throwable e) {
            failed.add(shortName + "->" + name + " 类缺失");
        }
    }

    private static void check(Class<?> owner, String name, Class<?>[] args,
                              List<String> passed, List<String> failed) {
        try {
            Method method = owner.getDeclaredMethod(name, args);
            passed.add(owner.getName() + "->" + method.getName());
        } catch (Throwable e) {
            failed.add(owner.getName() + "->" + name + " 签名不匹配");
        }
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append('\n');
            out.append(value);
        }
        return out.toString();
    }

    private static String safeMessage(Throwable e) {
        return e.getMessage() == null ? "未知错误" : e.getMessage();
    }

    static final class Result {
        final boolean success;
        final String versionName;
        final long versionCode;
        final int passedCount;
        final List<String> failed;

        private Result(boolean success, String versionName, long versionCode,
                       int passedCount, List<String> failed) {
            this.success = success;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.passedCount = passedCount;
            this.failed = failed;
        }

        static Result failure(String error) {
            List<String> failed = new ArrayList<>();
            failed.add(error);
            return new Result(false, "未知", 0, 0, failed);
        }

        String message() {
            if (success) return "已生成本机配置\n钉钉 " + versionName + " (" + versionCode + ")\n通过 " + passedCount + " 项检查";
            return "配置生成失败\n" + join(failed);
        }
    }
}
