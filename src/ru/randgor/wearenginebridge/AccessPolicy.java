package ru.randgor.wearenginebridge;

/** Host compatibility and scope only; clients are not identified or filtered. */
public final class AccessPolicy {
    public static final String HEALTH = "com.huawei.health";
    public static final String PROCESS = HEALTH + ":DaemonService";
    public static final String PHONE_PROCESS = HEALTH + ":PhoneService";
    public static final long HEALTH_VERSION = 1700008300L;
    public static final String SCOPE = "com.huawei.hiwear.devicemanager";
    public static final String CHANNEL = "wearEngine";
    private AccessPolicy() {}
    public static boolean isProcess(String pkg, String process) {
        return HEALTH.equals(pkg) && (PROCESS.equals(process) || PHONE_PROCESS.equals(process));
    }
    public static boolean isHost(long version) {
        return version == HEALTH_VERSION;
    }
    public static boolean isRequest(Object[] args) {
        return args != null && args.length == 4
            && SCOPE.equals(args[0]) && CHANNEL.equals(args[3]);
    }
}
