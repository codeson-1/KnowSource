package com.knowsource.cache;

/**
 * Embedding 缓存模式。
 *
 * <ul>
 *   <li>{@link #DISABLED} —— 关闭缓存，每次直调 DashScope（默认值，等价于引入前行为）</li>
 *   <li>{@link #L1_ONLY} —— 仅启用 Caffeine 本地堆内缓存（单实例首选）</li>
 *   <li>{@link #L1_L2} —— Caffeine L1 + Redisson 二进制 L2 双级（多实例水平扩容后启用）</li>
 * </ul>
 *
 * <p>选型依据见 docs/architecture/KnowSource-RAG-架构设计文档.md §15.5 与
 * docs/architecture/Redis引入设计文档.md。
 */
public enum EmbeddingCacheMode {
    DISABLED,
    L1_ONLY,
    L1_L2
}
