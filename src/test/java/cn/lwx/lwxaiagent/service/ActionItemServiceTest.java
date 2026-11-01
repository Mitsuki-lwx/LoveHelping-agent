package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.ActionItem;
import cn.lwx.lwxaiagent.mapper.ActionItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行动卡服务测试（V21，2026-09-08 产品闭环 ②）。
 * 覆盖：从三牌回复抽取行动项（优先进击牌）· 去重 · 归属校验 · 完成/跳过。
 */
class ActionItemServiceTest {

    private final ActionItemMapper mapper = mock(ActionItemMapper.class);
    private final ActionItemService service = new ActionItemService(mapper);

    private static final String REPLY = """
            先抱抱你。
            🛡️ 安全牌：简单说"我有点累，想和你聊聊"对方可能反应：愿意听你说
            ⚡ 进击牌：约个时间坐下来，把在意的点一条条说清楚对方可能反应：开始认真对待
            🌸 后撤牌：先各自冷静一晚再说对方可能反应：松一口气
            """;

    @Test
    void createFromReply_picksBoldCard() {
        when(mapper.selectCount(any())).thenReturn(0L);

        ActionItem item = service.createFromReply("u1", "chat-1", REPLY);

        assertThat(item).isNotNull();
        assertThat(item.getContent()).contains("约个时间坐下来");
        assertThat(item.getSource()).isEqualTo("ADVICE_BOLD");
        assertThat(item.getStatus()).isEqualTo("OPEN");
        verify(mapper).insert(any(ActionItem.class));
    }

    @Test
    void createFromReply_blankOrNoTiers_returnsNull() {
        assertThat(service.createFromReply("u1", "c", null)).isNull();
        assertThat(service.createFromReply("u1", "c", "只是一句普通安慰，没有建议")).isNull();
        verify(mapper, never()).insert(any(ActionItem.class));
    }

    @Test
    void createFromReply_skipsDuplicateOpenItem() {
        when(mapper.selectCount(any())).thenReturn(1L);

        ActionItem item = service.createFromReply("u1", "chat-1", REPLY);

        assertThat(item).isNull();
        verify(mapper, never()).insert(any(ActionItem.class));
    }

    /** 归属校验：他人行动项不可标记完成/删除 */
    @Test
    void markDone_otherUsersItem_returnsFalse() {
        ActionItem other = new ActionItem();
        other.setId(9L);
        other.setUserId("someone-else");
        when(mapper.selectById(9L)).thenReturn(other);

        assertThat(service.markDone("u1", 9L)).isFalse();
        assertThat(service.remove("u1", 9L)).isFalse();
    }

    @Test
    void markDone_ownItem_updatesStatus() {
        ActionItem mine = new ActionItem();
        mine.setId(3L);
        mine.setUserId("u1");
        mine.setStatus("OPEN");
        when(mapper.selectById(3L)).thenReturn(mine);
        when(mapper.updateById(any(ActionItem.class))).thenReturn(1);

        assertThat(service.markDone("u1", 3L)).isTrue();

        ArgumentCaptor<ActionItem> cap = ArgumentCaptor.forClass(ActionItem.class);
        verify(mapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("DONE");
        assertThat(cap.getValue().getDoneAt()).isNotNull();
    }

    @Test
    void listOpen_anonymousReturnsEmpty() {
        assertThat(service.listOpen(null)).isEqualTo(List.of());
    }

    @Test
    void listOpen_queriesOpenOnly() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        service.listOpen("u1");
        verify(mapper).selectList(any(LambdaQueryWrapper.class));
    }
}
