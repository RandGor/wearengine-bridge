package ru.randgor.wearenginebridge;

/** Host-side boundaries; does not claim Android/LSPosed integration coverage. */
public final class AccessPolicyTest {
    private static int checks;
    private static void check(String name, boolean result) {
        checks++;
        if (!result) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        check("correct process", AccessPolicy.isProcess(AccessPolicy.HEALTH, AccessPolicy.PROCESS));
        check("Frida process included", AccessPolicy.isProcess(AccessPolicy.HEALTH, AccessPolicy.PHONE_PROCESS));
        check("widget process excluded", !AccessPolicy.isProcess(AccessPolicy.HEALTH, AccessPolicy.HEALTH + ":widgetProvider"));
        check("other host with PhoneService excluded", !AccessPolicy.isProcess("other", AccessPolicy.PHONE_PROCESS));
        check("main process excluded", !AccessPolicy.isProcess(AccessPolicy.HEALTH, AccessPolicy.HEALTH));
        check("other host excluded", !AccessPolicy.isProcess("other", AccessPolicy.PROCESS));
        check("tested host identified", AccessPolicy.isTestedHost(AccessPolicy.TESTED_HEALTH_VERSION));
        check("changed host version requires warning", !AccessPolicy.isTestedHost(AccessPolicy.TESTED_HEALTH_VERSION + 1));
        for (int uid : new int[]{10123, 1010123, 1000, -1}) {
            check("no UID filter " + uid, AccessPolicy.isRequest(new Object[]{AccessPolicy.SCOPE, 0, uid, AccessPolicy.CHANNEL}));
        }
        check("other scope", !AccessPolicy.isRequest(new Object[]{"other", 123, 10123, AccessPolicy.CHANNEL}));
        check("other channel", !AccessPolicy.isRequest(new Object[]{AccessPolicy.SCOPE, 123, 10123, "HiHealth_HiHwKitAssistant"}));
        check("null scope", !AccessPolicy.isRequest(new Object[]{null, 123, 10123, AccessPolicy.CHANNEL}));
        check("null channel", !AccessPolicy.isRequest(new Object[]{AccessPolicy.SCOPE, 123, 10123, null}));
        check("null args", !AccessPolicy.isRequest(null));
        check("wrong arity", !AccessPolicy.isRequest(new Object[]{AccessPolicy.SCOPE, AccessPolicy.CHANNEL}));
        System.out.println("PASS: " + checks + " policy boundary checks");
    }
}
