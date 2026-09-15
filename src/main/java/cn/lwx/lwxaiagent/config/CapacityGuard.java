package cn.lwx.lwxaiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 容量口径一致性校验（Phase 6，ADR-29）：
 * 在线闸门（{@code app.online.max-inflight}）不得高于网关并发上限
 * （{@code app.llm.max-concurrent-calls}）。
 *
 * <p><b>为什么</b>：闸门是"对外准入"，网关并发是"对内出口"。若闸门更高，
 * 被放行的请求会在网关处二次排队，用户侧表现为"莫名其妙地慢"而非清晰的过载提示，
 * 且把排队位置藏在了两层之间，难以归因。两者相等即"放进来多少就能同时发多少"。</p>
 *
 * <p><b>上游约束</b>：厂商并发上限（2026-09-15 实测 glm-4-flash ≈ 24）是真正的天花板。
 * 把两者一起调高到超过该值，只会把压力透传成厂商 429 并被重试放大，因此本类只做一致性
 * 校验与可见性提示；提高上限需先提升厂商配额（另立任务）。</p>
 */
@Slf4j
@Component
public class CapacityGuard {

    public static final int VENDOR_CONCURRENCY_MEASURED = 24;

    public CapacityGuard(@Value("${app.online.max-inflight:8}") int gate,
                         @Value("${app.llm.max-concurrent-calls:24}") int gateway,
                         @Value("${app.online.wait-ms:3000}") long waitMs,
                         @Value("${app.online.queue-capacity:24}") int queueCapacity) {
        if (gate > gateway) {
            throw new IllegalStateException(String.format(
                    "容量配置不一致：app.online.max-inflight=%d 高于 app.llm.max-concurrent-calls=%d。"
                            + "闸门高于网关会让请求在网关处二次排队（用户侧表现为无提示的变慢），"
                            + "请把闸门设为不高于网关并发。", gate, gateway));
        }
        log.info("capacity: gate={} gateway={} queue={} waitMs={} | vendor concurrency <= {} (measured 2026-09-15, glm-4-flash)",
                gate, gateway, queueCapacity, waitMs, VENDOR_CONCURRENCY_MEASURED);
        if (gateway > VENDOR_CONCURRENCY_MEASURED) {
            log.warn("capacity: 网关并发 {} 高于实测厂商上限 {}——超额会被厂商立即 429 并由重试放大；"
                            + "提高上限需先提升厂商配额（见 docs/phase6-concurrency/spec.md）",
                    gateway, VENDOR_CONCURRENCY_MEASURED);
        }
    }
}
