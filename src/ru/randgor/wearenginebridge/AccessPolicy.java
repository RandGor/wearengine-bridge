package ru.randgor.wearenginebridge;

/** Host/process and scope boundaries; tested version is diagnostic information only. */
public final class AccessPolicy {
    public static final String HEALTH = "com.huawei.health";
    public static final String PROCESS = HEALTH + ":DaemonService";
    public static final String PHONE_PROCESS = HEALTH + ":PhoneService";
    public static final long TESTED_HEALTH_VERSION = 1700008300L;
    public static final String SCOPE = "com.huawei.hiwear.devicemanager";
    public static final String CHANNEL = "wearEngine";
    private AccessPolicy() {}
    public static boolean isProcess(String pkg, String process) {
        return HEALTH.equals(pkg) && (PROCESS.equals(process) || PHONE_PROCESS.equals(process));
    }
    /** Used only to warn about untested versions, never to deny activation. */
    public static boolean isTestedHost(long version) {
        return version == TESTED_HEALTH_VERSION;
    }
    public static boolean isRequest(Object[] args) {
        return args != null && args.length == 4
            && SCOPE.equals(args[0]) && CHANNEL.equals(args[3]);
    }
}
