package com.lxb.server.system;

import java.lang.reflect.Method;

/**
 * Hidden API 绕过工具
 *
 * Android 9 (P) 及以上版本限制了对 Hidden API 的访问。
 * 此工具使用反射绕过这些限制，允许访问 UiAutomation 等系统 API。
 *
 * 必须在 Main 入口处尽早调用 bypass() 方法。
 */
public class HiddenApiBypass {

    private static final String TAG = "[LXB][HiddenAPI]";
    private static boolean bypassed = false;

    /**
     * 绕过 Hidden API 限制
     *
     * 应在应用启动时尽早调用此方法。
     *
     * @return true 如果绕过成功或已经绕过，false 如果所有方法都失败
     */
    public static synchronized boolean bypass() {
        if (bypassed) {
            System.out.println(TAG + " Already bypassed");
            return true;
        }

        // 方法 1: 设置 VM 豁免列表 (Android 9-14)
        if (tryVMRuntimeExemptions()) {
            bypassed = true;
            System.out.println(TAG + " VMRuntime exemptions method succeeded");
            return true;
        }

        // 方法 2: 通过反射设置隐藏 API 策略 (备用)
        if (tryReflectionPolicy()) {
            bypassed = true;
            System.out.println(TAG + " Reflection policy method succeeded");
            return true;
        }

        // 方法 3: 双重反射技术 (Android 11+)
        if (tryDoubleReflection()) {
            bypassed = true;
            System.out.println(TAG + " Double reflection method succeeded");
            return true;
        }

        System.err.println(TAG + " All bypass methods failed!");
        return false;
    }

    /**
     * 方法 1: VMRuntime 豁免列表
     *
     * 通过设置 VM 的 hidden API 豁免列表来绕过限制。
     * 这是最可靠的方法，适用于 Android 9-14。
     */
    private static boolean tryVMRuntimeExemptions() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);

            Method setHiddenApiExemptions = vmRuntime.getDeclaredMethod(
                    "setHiddenApiExemptions", String[].class);
            setHiddenApiExemptions.setAccessible(true);

            // "L" 前缀匹配所有类
            setHiddenApiExemptions.invoke(runtime, (Object) new String[]{"L"});

            return true;

        } catch (Exception e) {
            System.err.println(TAG + " VMRuntime exemptions failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 方法 2: 反射策略设置
     *
     * 通过反射修改隐藏 API 策略字段。
     */
    private static boolean tryReflectionPolicy() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);

            // 尝试设置 hidden API 访问标志
            Method setTargetSdkVersionNative = vmRuntime.getDeclaredMethod(
                    "setTargetSdkVersionNative", int.class);
            setTargetSdkVersionNative.setAccessible(true);

            // 设置为较低的 SDK 版本以绕过检查
            setTargetSdkVersionNative.invoke(runtime, 27); // Android 8.1

            return true;

        } catch (Exception e) {
            System.err.println(TAG + " Reflection policy failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 方法 3: 双重反射技术
     *
     * 使用双重反射来访问被限制的 API。
     * 通过 Class.forName 和 getDeclaredMethod 的组合绕过检查。
     */
    private static boolean tryDoubleReflection() {
        try {
            // 获取 Class 类的 forName 方法
            Method forName = Class.class.getDeclaredMethod(
                    "forName", String.class);

            // 获取 Class 类的 getDeclaredMethod 方法
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);

            // 使用双重反射获取 VMRuntime
            Class<?> vmRuntime = (Class<?>) forName.invoke(null,
                    "dalvik.system.VMRuntime");

            Method getRuntime = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "getRuntime", null);
            Object runtime = getRuntime.invoke(null);

            Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "setHiddenApiExemptions",
                    new Class[]{String[].class});
            setHiddenApiExemptions.invoke(runtime, (Object) new String[]{"L"});

            return true;

        } catch (Exception e) {
            System.err.println(TAG + " Double reflection failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查是否已绕过 Hidden API 限制
     *
     * @return true 如果已绕过
     */
    public static boolean isBypassed() {
        return bypassed;
    }

    /**
     * 测试 Hidden API 访问是否正常
     *
     * @return true 如果可以访问 Hidden API
     */
    public static boolean testAccess() {
        try {
            // 尝试访问一个 Hidden API 来验证
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = activityThread.getDeclaredMethod(
                    "currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object thread = currentActivityThread.invoke(null);

            boolean success = thread != null;
            System.out.println(TAG + " Access test: " + (success ? "PASS" : "FAIL"));
            return success;

        } catch (Exception e) {
            System.err.println(TAG + " Access test failed: " + e.getMessage());
            return false;
        }
    }
}
