package cn.lwx.mcpserver.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class DatePlannerTool {

    @Tool(description = "根据城市、关系阶段、预算推荐约会方案。先调用 getWeather 查天气后再调用本工具，能给出结合天气的建议。stage 取值: first(第一次约会), early(暧昧/追求期), dating(恋爱中), longtime(长期情侣), married(已婚)")
    public String planDate(
            @ToolParam(description = "城市中文名") String city,
            @ToolParam(description = "关系阶段: first/early/dating/longtime/married") String stage,
            @ToolParam(description = "预算范围: low(200以内), medium(200-500), high(500+)") String budget,
            @ToolParam(description = "天气状况描述，由 getWeather 返回结果传入，如 晴、雨等") String weatherCondition) {

        boolean isRainy = weatherCondition != null
                && (weatherCondition.contains("雨") || weatherCondition.contains("雪") || weatherCondition.contains("阴"));

        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(city).append(" 约会方案 ===\n\n");

        if (isRainy) {
            sb.append("☔ 天气不佳建议室内活动：\n");
        } else {
            sb.append("☀️ 天气适合外出：\n");
        }
        sb.append("\n");

        switch (stage) {
            case "first" -> {
                sb.append("【第一次约会】\n");
                sb.append("目标：轻松破冰，低压力，30-60分钟可结束\n");
                if (isRainy) {
                    sb.append("• 咖啡馆/茶饮店 — 安静环境方便聊天\n");
                    sb.append("• 书店/文创店 — 边逛边聊，有话题素材\n");
                    sb.append("• 室内游乐场（如抓娃娃） — 轻松有互动\n");
                } else {
                    sb.append("• 公园散步 — 免费自然，边走边聊\n");
                    sb.append("• 校园/艺术区漫步 — 文艺氛围\n");
                    sb.append("• 甜品店 — 甜蜜轻松\n");
                }
                sb.append("预算建议：100-200，首次不宜太重\n");
            }
            case "early" -> {
                sb.append("【暧昧/追求期】\n");
                sb.append("目标：升级关系，展示用心，创造好感\n");
                if (isRainy) {
                    sb.append("• 手工DIY体验（陶艺/烘焙） — 协作互动\n");
                    sb.append("• 电影院 — 选爱情/喜剧片，看完有话题\n");
                    sb.append("• 室内射箭/保龄球 — 趣味竞技\n");
                } else {
                    sb.append("• 游乐园/主题乐园 — 刺激项目拉近距离\n");
                    sb.append("• 野餐 — 准备对方喜欢的食物，显用心\n");
                    sb.append("• 看日落 + 散步 — 浪漫加分的经典组合\n");
                }
                sb.append("预算建议：200-400，显用心但不过度\n");
            }
            case "dating" -> {
                sb.append("【恋爱中】\n");
                sb.append("目标：维持新鲜感，创造共同回忆\n");
                if (isRainy) {
                    sb.append("• 密室逃脱/剧本杀 — 团队协作增进默契\n");
                    sb.append("• 一起做饭（家中或烹饪教室） — 亲密互动\n");
                    sb.append("• 室内游泳/温泉 — 放松身心\n");
                } else {
                    sb.append("• 短途一日游/周边景点 — 像小旅行\n");
                    sb.append("• 采摘/农家乐 — 体验式的约会\n");
                    sb.append("• 骑行/徒步 — 一起运动，健康约会\n");
                }
                sb.append("预算建议：300-600，定期换花样保持新鲜\n");
            }
            case "longtime" -> {
                sb.append("【长期情侣】\n");
                sb.append("目标：对抗平淡，制造惊喜\n");
                if (isRainy) {
                    sb.append("• 在家电影马拉松 + 自制火锅 — 舒适温馨\n");
                    sb.append("• 酒店Staycation — 换个环境换心情\n");
                    sb.append("• 按摩/SPA双人套餐 — 放松享受\n");
                } else {
                    sb.append("• 周末两天一夜小旅行 — 跳出日常\n");
                    sb.append("• 看演唱会/音乐节 — 共同热爱\n");
                    sb.append("• 一起上兴趣课（舞蹈/油画/乐器） — 共同成长\n");
                }
                sb.append("预算建议：500-1000，投资感情保持热度\n");
            }
            case "married" -> {
                sb.append("【已婚】\n");
                sb.append("目标：找回恋爱感，给彼此独处时间\n");
                if (isRainy) {
                    sb.append("• 把孩子交给父母，二人世界餐厅约会\n");
                    sb.append("• 一起回忆老照片/旧物 — 重温初心\n");
                    sb.append("• 家里浪漫晚餐 + 红酒\n");
                } else {
                    sb.append("• 重游第一次约会的地方\n");
                    sb.append("• 双人户外活动（高尔夫/骑马/帆船）\n");
                    sb.append("• 周末度假村 — 暂时逃离家庭责任\n");
                }
                sb.append("预算建议：800+，偶尔奢侈一次很有必要\n");
            }
            default -> sb.append("未知关系阶段，请重新输入\n");
        }

        if (budget != null) {
            sb.append("\n【预算参考】\n");
            int max;
            switch (budget) {
                case "low" -> max = 200;
                case "medium" -> max = 500;
                case "high" -> max = 500;
                default -> max = 500;
            }
            sb.append("你设定的预算在 ").append(max).append(" 以内，");
            if ("high".equals(budget)) {
                sb.append("可选择豪华方案，提前预约确保体验\n");
            } else {
                sb.append("上述方案大多在预算范围内\n");
            }
        }

        sb.append("\n💡 小贴士：提前预订、注意着装、手机静音、专注对方");
        return sb.toString();
    }
}
