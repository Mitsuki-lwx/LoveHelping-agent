package cn.lwx.lwxaiagent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class LoveAppTest {

    @Resource
    private LoveApp loveApp;
    @Test
    void dochat() {
        String ChatId = UUID.randomUUID().toString();
        //第一次
        String message = "你好，我是小林";
        String answer = loveApp.doChat(message, ChatId);
        //第二次
        message = "我想谈和xxx恋爱";
        answer = loveApp.doChat(message, ChatId);
        Assertions.assertNotNull( answer);//断言不为空，Assertions是JUnit5提供的断言类，可以测试这里的结果是否为空
        //第三次
        message = "我刚刚跟你说我叫什么";
        answer = loveApp.doChat(message, ChatId);
        Assertions.assertNotNull( answer);

    }
    @Test
    void doChatWithReport() {
        String ChatId = UUID.randomUUID().toString();
        String message = "你好，我是小林,我想让另一半xxx更爱我，我不知道怎么办";
        LoveApp.LoveReport loveReport = loveApp.doChatWithReport(message, ChatId);
        Assertions.assertNotNull(loveReport);
    }

    @Test
    void doChat() {
    }

    @Test
    void doChatWithRAG() {
        String ChatId = UUID.randomUUID().toString();
        String message = "你好，我是小林,我结婚了，怎么维护亲密关系，说一下你是调用本地的知识库还是百炼的，消耗了多少token";
        loveApp.doChatWithRAG(message, ChatId);
    }
    @Test
    void doChatWithTools() {

//        testMessage("周末想带女朋友去上海约会，推荐几个适合情侣的小众打卡地？");
//
//
//        testMessage("最近和对象吵架了，看看编程导航网站（codefather.cn）的其他情侣是怎么解决矛盾的？");
//
//
//       testMessage("直接下载一张适合做手机壁纸的星空情侣图片为文件");
//
//
//        testMessage("执行 Python3 脚本来生成数据分析报告");


        testMessage("我想找一张《流浪地球1》的电影海报送给女朋友，这样合适吗？你可以直接下载我一份吗，她很喜欢" +
                "" +
                "");


//        testMessage("生成一份‘七夕约会计划’PDF，包含餐厅预订、活动流程和礼物清单");
    }

    private void testMessage(String message) {
        String chatId = UUID.randomUUID().toString();
        String answer = loveApp.doChatWithTools(message, chatId);
        Assertions.assertNotNull(answer);
    }
    @Test
    void doChatWithMcp() {
        String chatId = UUID.randomUUID().toString();

        String message = "帮我搜索一些哄另一半开心的图片";
        String answer =  loveApp.doChatWithMCP(message, chatId);
    }



}