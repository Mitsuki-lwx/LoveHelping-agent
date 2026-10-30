package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.entity.Complaint;
import cn.lwx.lwxaiagent.mapper.ComplaintMapper;
import cn.lwx.lwxaiagent.vo.ComplaintVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * <h1>投诉举报服务（V34 合规）</h1>
 *
 * <h2>校验（服务端硬校验，不信任前端）</h2>
 * <ul>
 *   <li>{@code category} 必须是 {@link #CATEGORIES} 之一；</li>
 *   <li>{@code content} 非空且 ≤ {@link #MAX_CONTENT} 字。</li>
 * </ul>
 *
 * <h2>限流</h2>
 * <p>同一用户 <b>24 小时内最多 {@link #MAX_PER_DAY} 条</b>：Redis 计数
 * {@code complaint:quota:{userId}}，首次 INCR 时设 24 小时 TTL（写法照抄
 * {@link EmailVerificationService} 的配额计数，不另造一套）。超限抛 429。</p>
 *
 * <p>⚠️ 顺序：<b>先校验、再限流、最后落库</b>——非法请求不该消耗配额。</p>
 */
@Slf4j
@Service
public class ComplaintService {

    /** 投诉分类（与迁移 V34 的列注释一致）。 */
    public static final Set<String> CATEGORIES = Set.of("CONTENT", "PRIVACY", "ABUSE", "OTHER");
    /** 处理状态。 */
    public static final Set<String> STATUSES = Set.of("OPEN", "PROCESSING", "CLOSED");

    /** 单条投诉正文上限（与 complaint.content VARCHAR(1000) 对齐）。 */
    static final int MAX_CONTENT = 1000;
    /** 24 小时窗口内最多提交条数。 */
    static final int MAX_PER_DAY = 5;
    /** 限流窗口。 */
    static final Duration WINDOW = Duration.ofHours(24);
    /** 管理端列表单页上限（防止 12k+ 级数据被一次拉爆）。 */
    static final int ADMIN_MAX_LIMIT = 200;

    private final ComplaintMapper complaintMapper;
    private final StringRedisTemplate redis;

    public ComplaintService(ComplaintMapper complaintMapper, StringRedisTemplate redis) {
        this.complaintMapper = complaintMapper;
        this.redis = redis;
    }

    /**
     * 提交一条投诉：校验 → 限流 → 落库（状态 OPEN）。
     *
     * @return 新记录的 id（字符串，便于接口回执）
     * @throws BizException 400 分类/正文非法；429 超过 24 小时窗口配额
     */
    public String submit(String userId, String tenantId, String category, String content, String contact) {
        if (category == null || !CATEGORIES.contains(category)) {
            throw new BizException(400, "投诉分类无效，必须为 CONTENT/PRIVACY/ABUSE/OTHER 之一");
        }
        if (content == null || content.isBlank()) {
            throw new BizException(400, "投诉内容不能为空");
        }
        if (content.length() > MAX_CONTENT) {
            throw new BizException(400, "投诉内容不能超过 1000 字");
        }
        enforceRateLimit(userId);

        Complaint c = new Complaint();
        c.setTenantId(tenantId == null || tenantId.isBlank() ? "default" : tenantId);
        c.setUserId(userId);
        c.setCategory(category);
        c.setContent(content);
        c.setContact(contact);
        c.setStatus("OPEN");
        complaintMapper.insert(c);
        log.info("Complaint submitted: user={}, category={}, id={}", userId, category, c.getId());
        return String.valueOf(c.getId());
    }

    /**
     * 同一用户 24 小时内最多 {@link #MAX_PER_DAY} 条。
     *
     * <p>计数 {@code complaint:quota:{userId}}：首次 INCR 时设 24 小时过期；
     * 超过上限抛 {@link BizException} 429。</p>
     */
    void enforceRateLimit(String userId) {
        String key = "complaint:quota:" + userId;
        Long used = redis.opsForValue().increment(key);
        if (used != null && used == 1L) {
            redis.expire(key, WINDOW);
        }
        if (used != null && used > MAX_PER_DAY) {
            throw new BizException(429, "提交过于频繁，请 24 小时后再试");
        }
    }

    /** 当前用户自己的投诉，按提交时间倒序。 */
    public List<ComplaintVO> listMine(String userId) {
        List<Complaint> rows = complaintMapper.selectList(new LambdaQueryWrapper<Complaint>()
                .eq(Complaint::getUserId, userId)
                .orderByDesc(Complaint::getCreatedAt)
                .orderByDesc(Complaint::getId));
        return rows.stream().map(c -> toVo(c, false)).toList();
    }

    /**
     * 管理端列表（可跨用户）。
     *
     * @param status 可选过滤；为空 = 全部（非法值由调用方先行拒绝）
     * @param limit  单页条数，归一化到 [1, {@link #ADMIN_MAX_LIMIT}]
     */
    public List<ComplaintVO> listForAdmin(String status, Integer limit) {
        int n = normalizeLimit(limit);
        LambdaQueryWrapper<Complaint> q = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            q.eq(Complaint::getStatus, status);
        }
        q.orderByDesc(Complaint::getCreatedAt).orderByDesc(Complaint::getId);
        q.last("LIMIT " + n);   // n 已归一化为 [1,200] 的整数，非用户拼接
        return complaintMapper.selectList(q).stream().map(c -> toVo(c, true)).toList();
    }

    /** 管理端更新处理状态。 */
    public void updateStatus(Long id, String status) {
        if (status == null || !STATUSES.contains(status)) {
            throw new BizException(400, "status 只能为 OPEN/PROCESSING/CLOSED");
        }
        Complaint c = complaintMapper.selectById(id);
        if (c == null) {
            throw new BizException(404, "投诉不存在");
        }
        c.setStatus(status);
        complaintMapper.updateById(c);
        log.info("Complaint {} status -> {}", id, status);
    }

    static int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return 50;
        }
        return Math.min(limit, ADMIN_MAX_LIMIT);
    }

    private static ComplaintVO toVo(Complaint c, boolean withUser) {
        return new ComplaintVO(c.getId(), c.getCategory(), c.getContent(), c.getContact(),
                c.getStatus(), c.getCreatedAt(), withUser ? c.getUserId() : null);
    }
}
