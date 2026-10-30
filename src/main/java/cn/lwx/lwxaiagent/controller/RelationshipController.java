package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.audit.AuditLog;
import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.entity.RelationshipProfile;
import cn.lwx.lwxaiagent.mapper.RelationshipProfileMapper;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关系档案编辑接口（ADR-14，关系档案用户可见可改）。
 * <p>
 * relationship_profile 的数据会经 {@code MemoryStore.retrieveAsContext} 拼进
 * {@code <memory_profile>} 注入模型提示词 —— 用户一旦能设置 stage / partnerAlias，
 * AI 回信的措辞与称呼就会跟着变。这是本接口的价值所在。
 * </p>
 * <p>
 * 归属：只作用于当前 JWT 用户（{@link TenantContext}）。
 * 行不存在 = 用户还没填过（正常状态），GET 返回空值而非 404。
 * </p>
 */
@RestController
@RequestMapping("/profile/relationship")
public class RelationshipController {

    /**
     * stage 受控枚举 —— ⛔ 只接受这些值。
     * <p>非法值必须报错且**不落库**：该字段会被直接拼进模型提示词，
     * 静默存进去等于让用户往系统提示词里写任意文本。</p>
     */
    private static final List<String> ALLOWED_STAGES =
            List.of("单身", "暧昧中", "恋爱中", "异地恋", "已婚", "其他");

    /** partnerAlias 最大长度（与 V32 的 VARCHAR(32) 一致） */
    private static final int ALIAS_MAX_LEN = 32;

    private final RelationshipProfileMapper profileMapper;

    public RelationshipController(RelationshipProfileMapper profileMapper) {
        this.profileMapper = profileMapper;
    }

    /**
     * 查看我的关系档案。行不存在时返回 {@code stage/partnerAlias/updatedAt} 全为 null，
     * <b>不是 404</b>（用户还没填过是正常状态）。
     */
    @GetMapping
    public Result<Map<String, Object>> get() {
        String userId = requireUserId();
        return Result.ok(stateOf(profileMapper.selectById(userId)));
    }

    /**
     * 更新（或首次创建）关系档案。
     * <p>Body（两个字段均可选，只更新传入的非空字段）：
     * {@code {"stage": "恋爱中", "partnerAlias": "宝宝"}}。</p>
     * <ul>
     *   <li>stage 非受控枚举 → 400，且不落库</li>
     *   <li>partnerAlias trim 后 &gt; 32 字符 → 400，且不落库</li>
     *   <li>两个字段都为空 → 不写库，message 为"没有需要更新的字段"（与 /auth/profile 语义一致）</li>
     *   <li>行不存在 → insert（upsert）</li>
     * </ul>
     */
    @AuditLog("relationship_profile_update")
    @PutMapping
    public Result<Map<String, Object>> update(@RequestBody(required = false) Map<String, String> body) {
        String userId = requireUserId();
        Map<String, String> b = body == null ? Map.of() : body;

        // ① stage：受控枚举校验（非法值报错且不落库）
        String rawStage = b.get("stage");
        String stage = rawStage == null ? null : rawStage.trim();
        if (stage != null && stage.isEmpty()) {
            stage = null;
        }
        if (stage != null && !ALLOWED_STAGES.contains(stage)) {
            throw new BizException(400, "stage 非法，只能是：" + String.join(" / ", ALLOWED_STAGES));
        }

        // ② partnerAlias：trim 后判空与长度
        String rawAlias = b.get("partnerAlias");
        String alias = rawAlias == null ? null : rawAlias.trim();
        if (alias != null && alias.isEmpty()) {
            alias = null;
        }
        if (alias != null && alias.length() > ALIAS_MAX_LEN) {
            throw new BizException(400, "partnerAlias 过长（最多 " + ALIAS_MAX_LEN + " 字符）");
        }

        // ③ 两个字段都为空：不写库
        RelationshipProfile existing = profileMapper.selectById(userId);
        if (stage == null && alias == null) {
            return new Result<>(200, "没有需要更新的字段", stateOf(existing));
        }

        // ④ upsert（行不存在就 insert）
        if (existing == null) {
            RelationshipProfile profile = new RelationshipProfile();
            profile.setUserId(userId);
            profile.setStage(stage);
            profile.setPartnerAlias(alias);
            profile.setLastUpdatedAt(LocalDateTime.now());
            profileMapper.insert(profile);
            return new Result<>(200, "已更新", stateOf(profile));
        }
        if (stage != null) {
            existing.setStage(stage);
        }
        if (alias != null) {
            existing.setPartnerAlias(alias);
        }
        existing.setLastUpdatedAt(LocalDateTime.now());
        profileMapper.updateById(existing);
        return new Result<>(200, "已更新", stateOf(existing));
    }

    /** 把档案行转成对外响应形状（行不存在则三个字段都为 null）。 */
    private static Map<String, Object> stateOf(RelationshipProfile profile) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stage", profile == null ? null : profile.getStage());
        data.put("partnerAlias", profile == null ? null : profile.getPartnerAlias());
        data.put("updatedAt", profile == null || profile.getLastUpdatedAt() == null
                ? null : profile.getLastUpdatedAt().toString());
        return data;
    }

    private String requireUserId() {
        String userId = TenantContext.getUserId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        return userId;
    }
}
