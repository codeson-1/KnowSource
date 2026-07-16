package com.knowsource.cache;

public final class CacheKeys {
    private CacheKeys() {
    }

    /** 用户缓存：user:<username> */
    public static String user(String username) {
        return "user:" + username;
    }

    /** KB 成员角色缓存：kbmember:<kbId>:<userId> */
    public static String kbMember(String kbId, long userId) {
        return "kbmember:" + kbId + ":" + userId;
    }

    /** KB 删除时批量清理：kbmember:<kbId>:* */
    public static String kbMemberPattern(String kbId) {
        return "kbmember:" + kbId + ":*";
    }

    /** AI 限流（按 channel：chat / embedding / rerank） */
    public static final String RATE_LIMIT_PREFIX = "ratelimit:ai:";

    /** AI 并发舱壁信号量 */
    public static final String SEMAPHORE_PREFIX = "semaphore:ai:";
}
