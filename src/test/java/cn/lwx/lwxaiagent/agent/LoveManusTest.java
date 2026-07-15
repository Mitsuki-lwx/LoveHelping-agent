package cn.lwx.lwxaiagent.agent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SpringBootTest
class LoveManusTest {
    @Resource
    private LoveManus loveManus;

    @Test
    void testDoLove() {
        String prompt = """
                我的另一半居住在佛山市南海区,请帮我找到 5 公里内合适的约会地点, 并结合一些网络图片,制定一份详细的约会计划, 并以 PDF 格式输出
                """;
        SseEmitter emitter = loveManus.runStream(prompt);
        org.junit.jupiter.api.Assertions.assertNotNull(emitter);
    }

}