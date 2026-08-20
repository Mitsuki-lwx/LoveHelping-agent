package cn.lwx.mcpserver.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * <h1>约会方案推荐工具</h1>
 *
 * <p>
 * 本类是一个 MCP（模型上下文协议，Model Context Protocol）工具类，
 * 根据用户所在的城市、与约会对象的关系阶段、预算范围，以及当前的天气状况，
 * 智能推荐适合的约会方案。
 * </p>
 *
 * <h2>设计思路</h2>
 * <p>
 * 本工具的设计核心是"场景化推荐"——不同的关系阶段有不同的约会目标和策略。
 * 同时根据天气状况（晴天 vs 雨天/雪天）分室内和室外两套方案，确保推荐的方案在现实中可行。
 * </p>
 *
 * <h2>与 WeatherTool 的协作</h2>
 * <p>
 * 本工具被设计为与 {@link cn.lwx.mcpserver.tool.WeatherTool WeatherTool} 配合使用。
 * 典型的调用链：
 * </p>
 * <ol>
 *   <li>用户询问"周末适合去哪里约会？"</li>
 *   <li>AI 模型首先调用 {@code WeatherTool#getWeather(city)} 查询目标城市的天气</li>
 *   <li>AI 模型拿到天气结果后，将天气状况文本作为参数传入本工具的 {@code weatherCondition} 参数</li>
 *   <li>本工具根据天气判断推荐室内还是室外活动</li>
 * </ol>
 *
 * <h2>关系阶段说明</h2>
 * <p>
 * 共支持 5 个关系阶段，每个阶段有不同的约会目标和策略：
 * </p>
 * <ul>
 *   <li><b>first（第一次约会）</b>：目标是轻松破冰、低压力、30-60 分钟内可结束的短约会</li>
 *   <li><b>early（暧昧/追求期）</b>：目标是升级关系、展示用心、通过协作互动创造好感</li>
 *   <li><b>dating（恋爱中）</b>：目标是维持新鲜感、通过体验式约会创造共同回忆</li>
 *   <li><b>longtime（长期情侣）</b>：目标是对抗平淡、通过旅行或共同成长活动制造惊喜</li>
 *   <li><b>married（已婚）</b>：目标是找回恋爱感、给彼此创造独处时间</li>
 * </ul>
 *
 * <h2>预算级别说明</h2>
 * <ul>
 *   <li><b>low</b>：200 元以内，适合经济实惠型约会</li>
 *   <li><b>medium</b>：200-500 元，中等预算，可覆盖大多数约会方案</li>
 *   <li><b>high</b>：500 元以上，可选择豪华方案，需要提前预约</li>
 * </ul>
 *
 * @author lwx
 * @version 1.0
 * @see Tool
 * @see ToolParam
 * @since 2025
 */
@Component
public class DatePlannerTool {

    /**
     * <h3>生成约会方案推荐（MCP 工具方法）</h3>
     *
     * <p>
     * 本方法通过 {@code @Tool} 注解暴露为 MCP 工具。
     * 当 AI 模型理解到用户希望获得约会建议时，会自动调用此方法。
     * </p>
     *
     * <h3>执行业务逻辑</h3>
     * <ol>
     *   <li><b>天气判断</b>：检查 {@code weatherCondition} 参数中是否包含"雨"、"雪"或"阴"字，
     *       以此判断是否为不利天气。基于此决定推荐室内还是室外活动。</li>
     *   <li><b>阶段匹配</b>：根据 {@code stage} 参数（first/early/dating/longtime/married）
     *       选择对应关系阶段的约会方案模板，再结合天气筛选具体方案。</li>
     *   <li><b>预算参考</b>：根据 {@code budget} 参数（low/medium/high），
     *       验证推荐的方案是否在用户的预算范围内，并给出预算建议。</li>
     *   <li><b>通用建议</b>：在结果末尾添加通用的约会小贴士。</li>
     * </ol>
     *
     * <h3>MCP Client 调用示例</h3>
     * <p>
     * 当用户在 AI 对话中说"我和女朋友在恋爱中，预算500左右，北京今天天气晴，有什么约会建议？"时：
     * </p>
     * <ol>
     *   <li>AI 模型首先调用 {@code WeatherTool} 获取北京天气</li>
     *   <li>然后调用本方法：{@code planDate("北京", "dating", "medium", "晴 25°C")}</li>
     *   <li>本方法返回针对"恋爱中"阶段的约会方案，结合晴天推荐户外活动</li>
     *   <li>AI 模型将结果呈现给用户</li>
     * </ol>
     *
     * @param city            目标城市的名称，使用中文，例如"北京"、"上海"、"深圳"。
     *                        由 AI 模型从用户问题中自动提取。
     * @param stage           与约会对象的关系阶段，必须是以下五个值之一：
     *                        <ul>
     *                          <li>{@code "first"} — 第一次约会，目标为轻松破冰</li>
     *                          <li>{@code "early"} — 暧昧/追求期，目标为升级关系</li>
     *                          <li>{@code "dating"} — 恋爱中，目标为维持新鲜感</li>
     *                          <li>{@code "longtime"} — 长期情侣，目标为对抗平淡</li>
     *                          <li>{@code "married"} — 已婚，目标为找回恋爱感</li>
     *                        </ul>
     *                        由 AI 模型根据对话上下文推断用户当前的关系阶段。
     * @param budget          预算范围，必须是以下三个值之一：
     *                        <ul>
     *                          <li>{@code "low"} — 200 元以内，经济实惠</li>
     *                          <li>{@code "medium"} — 200-500 元，中等预算</li>
     *                          <li>{@code "high"} — 500 元以上，可选择高档方案</li>
     *                        </ul>
     *                        由 AI 模型根据用户的预算描述推断。
     * @param weatherCondition 天气状况的文本描述，通常来自 {@code WeatherTool#getWeather} 的返回值。
     *                         例如："晴 25°C"、"雨 18°C"。本方法通过检测其中是否包含"雨"、
     *                         "雪"、"阴"等关键词来判断是否适合户外活动。
     *                         如果不确定天气，可以传入 {@code null} 或空字符串。
     * @return 格式化的约会方案推荐文本，包含以下内容：
     *         <ul>
     *           <li>城市名和天气提示图标（☔ 或 ☀️）</li>
     *           <li>对应关系阶段的约会目标说明</li>
     *           <li>结合天气筛选的 3 个具体约会方案</li>
     *           <li>对应阶段的预算建议</li>
     *           <li>用户预算的参考说明</li>
     *           <li>通用约会小贴士</li>
     *         </ul>
     *         此字符串将被 MCP Server 发送回 AI 模型，由 AI 整理后呈现给用户。
     */
    @Tool(description = "根据城市、关系阶段、预算推荐约会方案。先调用 getWeather 查天气后再调用本工具，能给出结合天气的建议。stage 取值: first(第一次约会), early(暧昧/追求期), dating(恋爱中), longtime(长期情侣), married(已婚)")
    public String planDate(
            @ToolParam(description = "城市中文名") String city,
            @ToolParam(description = "关系阶段: first/early/dating/longtime/married") String stage,
            @ToolParam(description = "预算范围: low(200以内), medium(200-500), high(500+)") String budget,
            @ToolParam(description = "天气状况描述，由 getWeather 返回结果传入，如 晴、雨等") String weatherCondition) {

        // 判断是否为不利天气（雨天、雪天或阴天），将影响活动类型推荐
        // 通过检测天气描述中的关键字"雨"、"雪"、"阴"来判断
        boolean isRainy = weatherCondition != null
                && (weatherCondition.contains("雨") || weatherCondition.contains("雪") || weatherCondition.contains("阴"));

        // 使用 StringBuilder 高效构建返回内容
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(city).append(" 约会方案 ===\n\n");

        // 根据天气给出活动类型提示（室内 vs 室外）
        if (isRainy) {
            sb.append("☔ 天气不佳建议室内活动：\n");
        } else {
            sb.append("☀️ 天气适合外出：\n");
        }
        sb.append("\n");

        // 使用 switch 表达式根据关系阶段匹配不同的约会方案
        // 每个阶段都有"目标说明"和"室内/室外"两套推荐方案
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
            // 如果用户输入了不识别的关系阶段，给出提示
            default -> sb.append("未知关系阶段，请重新输入\n");
        }

        // 根据用户指定的预算范围给出对应的预算参考说明
        if (budget != null) {
            sb.append("\n【预算参考】\n");
            int max;
            switch (budget) {
                case "low" -> max = 200;      // 低预算：200 元以内
                case "medium" -> max = 500;    // 中预算：500 元以内
                case "high" -> max = 500;      // 高预算：500 元以上（无上限，显示参考值）
                default -> max = 500;          // 未知预算级别，默认按中档处理
            }
            sb.append("你设定的预算在 ").append(max).append(" 以内，");
            if ("high".equals(budget)) {
                sb.append("可选择豪华方案，提前预约确保体验\n");
            } else {
                sb.append("上述方案大多在预算范围内\n");
            }
        }

        // 添加通用的约会小贴士，这是所有阶段都适用的建议
        sb.append("\n💡 小贴士：提前预订、注意着装、手机静音、专注对方");
        return sb.toString();
    }
}
