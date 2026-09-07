package com.arthenica.smartexception.java;

/**
 * ffmpeg-kit 的 POM 漏声明了 smartexception-java 依赖，且原库已从 Maven Central 下架。
 * 此处内置同包名最小实现，保证 FFmpegKitConfig 类初始化可用。
 */
public class Exceptions {

    public static String getStackTraceString(Throwable throwable) {
        return android.util.Log.getStackTraceString(throwable);
    }

    public static void registerRootPackage(String rootPackage) {
        // no-op
    }
}
