package com.knowsource.eval;

import java.nio.file.Path;

import com.knowsource.security.CurrentUser;
import com.knowsource.security.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;

/**
 * 评测系统共享常量与工具方法。
 */
public final class EvalConstants {

    private EvalConstants() {
    }

    // ── 路径常量 ──────────────────────────────────────────

    /** Golden Set 数据文件路径 */
    static final Path GOLDEN_SET_PATH = Path.of("docs/eval/golden-set.jsonl");

    /** 评测报告主文件路径 */
    static final Path REPORT_PATH = Path.of("docs/eval/report.md");

    /** 评测报告主文件路径（字符串形式，供 HTTP 响应使用） */
    public static final String REPORT_PATH_STR = "docs/eval/report.md";

    /** 历史归档目录路径 */
    static final Path REPORTS_DIR_PATH = Path.of("docs/eval/reports");

    // ── 语义常量 ──────────────────────────────────────────

    /** 期望拒答时在报告中的显示文字 */
    public static final String EXPECTED_REFUSAL = "拒答";

    /** ADMIN 角色标识 */
    static final String ROLE_ADMIN = "ADMIN";

    // ── 轮询参数 ──────────────────────────────────────────

    /** 轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 50;

    /** 文档入库等待最大轮询次数 */
    static final int MAX_INGEST_POLLS = 120;

    /** QA trace 等待最大轮询次数 */
    static final int MAX_TRACE_POLLS = 40;

    // ── 工具方法 ──────────────────────────────────────────

    /**
     * 休眠 50 毫秒，被中断时将线程标记为中断并抛出 IllegalStateException。
     */
    static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for eval state.", ex);
        }
    }

    /**
     * 校验当前用户是否为 ADMIN，否则抛出 AccessDeniedException。
     */
    public static void requireAdmin(CurrentUserService currentUserService) {
        CurrentUser currentUser = currentUserService.currentUser();
        if (!ROLE_ADMIN.equals(currentUser.globalRole())) {
            throw new AccessDeniedException("ADMIN access is required.");
        }
    }
}
