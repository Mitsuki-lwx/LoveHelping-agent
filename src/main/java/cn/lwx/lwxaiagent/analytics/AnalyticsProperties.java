package cn.lwx.lwxaiagent.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <h3>分析层配置（PM 看板的数据口径）</h3>
 *
 * <p><b>为什么需要 exclude-username-prefixes</b>：库里的 users 表绝大多数是 E2E/压测
 * 脚本造出来的合成账号（verify_* / load_* / soak_* / probe_* …），不排除的话
 * DAU、注册数、留存全被测试流量污染——所有数字都是垃圾。</p>
 *
 * <p><b>⛔ 纪律</b>：过滤必须<b>如实上报</b>——
 * {@code AnalyticsOverviewVO.excludedTestUsers} 必须返回命中前缀被排除的用户数，
 * 绝不允许静默丢弃（静默过滤 = 看板口径造假）。</p>
 */
@ConfigurationProperties(prefix = "app.analytics")
public class AnalyticsProperties {

    /**
     * 测试账号用户名前缀（逗号分隔）。username 命中任一前缀即视为合成账号，
     * 从活跃/注册/留存/漏斗等所有"人的口径"中排除。
     */
    private List<String> excludeUsernamePrefixes = defaultPrefixes();

    public List<String> getExcludeUsernamePrefixes() { return excludeUsernamePrefixes; }

    public void setExcludeUsernamePrefixes(List<String> excludeUsernamePrefixes) {
        this.excludeUsernamePrefixes = excludeUsernamePrefixes;
    }

    /** application.yml 的默认值解析结果（与 YAML 保持一致，YAML 缺失时兜底）。 */
    public static List<String> defaultPrefixes() {
        // ⛔ 排除清单是**枚举式**的，永远追不上脚本造账号的速度（实测本机 193 个前缀段）。
        //    所以：① 默认值尽量覆盖已知形态；② 接口用 DataQualityVO 自报"排除了多少、占比多高"，
        //    让看板的人自己判断能不能信 —— 比假装数字干净诚实。生产库应按自己的命名规范重配。
        return Arrays.stream(("verify_,load_,soak_,aev_,acceval_,probe_,pc_,rd_,lim_,agg_,l3in_,smoke_,ui_,kid_,qq_,brk_,min_,c_,sent_,mu_,adr31_,uiprobe_,dbg,e2e_,obs,adv,media_,mem2_,noise,tier,soakprobe,lfacc_,lf,memv,msgtest,spatest,tracerag,tracedbg,fakechk,rejchk,llmfail,gt_eval,rawprobe,burst,scroll,secb,secu,sense,canary,burst_,testuser,user1").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
