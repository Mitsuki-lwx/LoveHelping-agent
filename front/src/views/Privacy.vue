<template>
  <div class="legal-page">
    <div class="legal-inner">
      <div class="legal-top">
        <button class="legal-back" @click="$router.back()">← 返回</button>
        <span class="legal-stamp hand">隐</span>
      </div>

      <article class="letter-card fold legal-card">
        <header class="legal-head">
          <h1 class="hand legal-title">隐私政策</h1>
          <p class="legal-meta">生效日期：{{ EFFECTIVE_DATE }} · 与《用户服务协议》同时适用</p>
        </header>

        <p class="legal-lead">
          这份政策逐条说明：我们收集哪些数据、为什么收集、会送到哪些第三方、保存多久，
          以及你如何<strong>导出和删除</strong>自己的数据。清单与代码实现一一对应，不写"可能""适当"这类模糊说法。
        </p>

        <section class="legal-sec">
          <h2>一、我们收集哪些数据，用来做什么</h2>
          <div class="legal-table-wrap">
            <table class="legal-table">
              <thead>
                <tr><th>数据类别</th><th>具体内容</th><th>用途</th></tr>
              </thead>
              <tbody>
                <tr v-for="row in DATA_PRACTICES" :key="row.what">
                  <td class="t-key">{{ row.what }}</td>
                  <td>{{ row.detail }}</td>
                  <td>{{ row.why }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="legal-tip">我们不收集你的真实姓名、身份证号、通讯录或精确位置。</p>
        </section>

        <section class="legal-sec">
          <h2>二、数据会流向哪些第三方（出域清单）</h2>
          <p>
            对话需要调用外部大模型与安全判定服务，因此下列内容会离开我们的服务器。
            这份清单是<strong>逐项列出</strong>的，新增任何出域依赖都会同步更新本表。
          </p>
          <div class="legal-table-wrap">
            <table class="legal-table">
              <thead>
                <tr><th>接收方</th><th>域名</th><th>送出的内容</th><th>状态</th></tr>
              </thead>
              <tbody>
                <tr v-for="p in OUTBOUND_PARTIES" :key="p.party">
                  <td class="t-key">{{ p.party }}</td>
                  <td><code>{{ p.domain }}</code></td>
                  <td>{{ p.sent }}</td>
                  <td class="t-status">{{ p.status }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="legal-tip">
            其中 Langfuse 仅接收链路 trace 元数据、<strong>不含对话原文</strong>，且生产环境默认关闭。
          </p>
        </section>

        <section class="legal-sec">
          <h2>三、保存期限</h2>
          <ul>
            <li>{{ RETENTION.active }}</li>
            <li>{{ RETENTION.deleted }}</li>
            <li>{{ RETENTION.outbound }}</li>
          </ul>
        </section>

        <section class="legal-sec">
          <h2>四、对话内容是否用于提炼通用技巧（单独同意）</h2>
          <p>
            这件事<strong>默认不做</strong>。只有你在「我的小窝 → 允许用我的对话提炼通用技巧」里<strong>明确开启</strong>后，
            系统才会从你的对话中提炼<strong>通用沟通技巧</strong>（例如"冲突时先复述对方感受"这类方法性表述），
            沉淀进知识库供其他用户检索到。
          </p>
          <ul>
            <li>技巧文本会经模型<strong>改写</strong>，不保留你的原文，也不附带你的身份信息（用户名、邮箱、关系档案等一律不带）。</li>
            <li>关闭开关后，新产生的对话不再进入这一流程；你随时可以关闭，无需说明理由。</li>
            <li>注册时的总协议勾选<strong>不等于</strong>这项同意 —— 这是单独同意，开关默认关闭。</li>
          </ul>
        </section>

        <section class="legal-sec">
          <h2>五、你的权利</h2>
          <ul>
            <li>
              <strong>导出</strong>：在「我的小窝 → 我的数据」点击「导出我的数据」，即可下载一个 JSON 文件，
              内含你的资料、关系档案、记忆、行动清单与全部信件正文。
            </li>
            <li>
              <strong>删除</strong>：在「旧信存档」可删除单条对话；在「我的小窝 → 注销账号」可注销账号，
              注销后按级联规则删除你的对话、记忆与相关向量数据，且不可恢复。
            </li>
            <li><strong>更正</strong>：昵称、签名、关系档案与绑定邮箱都可以在「我的小窝」中随时自行修改。</li>
            <li>如你希望行使上述之外的查询、复制或删除请求，可通过第九条的联系方式提出。</li>
          </ul>
        </section>

        <section class="legal-sec">
          <h2>六、未成年人</h2>
          <p>
            未满 18 周岁的用户须在监护人同意下使用本服务。我们为未成年账号默认开启<strong>未成年人模式</strong>：
            每日对话条数上限 {{ MINOR.dailyLimit }} 条，{{ MINOR.quietStart }}–{{ MINOR.quietEnd }} 为休息时段。
            我们不会针对未成年人做个性化广告推荐（本服务本身也不含广告）。
          </p>
        </section>

        <section class="legal-sec">
          <h2>七、AI 生成内容的标识</h2>
          <p>
            本服务的全部对话回复均由 AI 生成：聊天界面每条 AI 回复旁带有「{{ AI_LABEL }}」标签；
            导出的数据文件顶层包含 <code>aiGenerated</code> 与 <code>aiGeneratedNote</code> 字段
            （其值为「{{ AI_EXPORT_NOTE }}」）。
          </p>
        </section>

        <section class="legal-sec">
          <h2>八、投诉举报</h2>
          <p>
            若你认为某项数据处理侵犯了你的权益，可通过「我的小窝 → 投诉举报」提交（分类选「隐私泄露」）。
            我们<strong>自受理之日起 {{ FEEDBACK_DAYS }} 个工作日内</strong>完成核查并反馈。
          </p>
        </section>

        <section class="legal-sec">
          <h2>九、联系方式</h2>
          <p v-if="contact.email || contact.phone">
            <template v-if="contact.email">邮箱：{{ contact.email }}</template>
            <template v-if="contact.email && contact.phone"> · </template>
            <template v-if="contact.phone">电话：{{ contact.phone }}</template>
          </p>
          <p v-else class="legal-placeholder">{{ contact.note }}</p>
        </section>

        <footer class="legal-foot">
          <span>生效日期：{{ EFFECTIVE_DATE }}</span>
          <router-link to="/terms" class="legal-link">阅读《用户服务协议》→</router-link>
        </footer>
      </article>
    </div>
  </div>
</template>

<script setup>
/**
 * 隐私政策（2026-10-13 合规改造新增）。
 *
 * ⛔ 出域清单**照抄** `docs/07-安全与合规.md` §6.1（该表本身就是本仓的数据出域事实源），
 *   并抽成 utils/legal.js 的 OUTBOUND_PARTIES —— 新增出域依赖时改一处即可，
 *   避免"文档列了 5 家、页面只写 3 家"这种一眼假的政策。
 * 保存期限也不是编的：删除的对话为软删 + 物理清除任务按 `app.retention.deleted-message-days`
 * （默认 30 天）执行（ADR-5）。
 */
import {
  LEGAL_EFFECTIVE_DATE as EFFECTIVE_DATE,
  LEGAL_CONTACT as contact,
  COMPLAINT_FEEDBACK_WORKING_DAYS as FEEDBACK_DAYS,
  MINOR_MODE as MINOR,
  AI_LABEL_TEXT as AI_LABEL,
  AI_EXPORT_NOTE,
  OUTBOUND_PARTIES,
  DATA_PRACTICES,
  RETENTION,
} from '../utils/legal.js'
</script>

<style scoped>
.legal-page { height: 100%; overflow-y: auto; background: var(--desk); padding: 24px 20px 48px; }
.legal-inner { max-width: 760px; margin: 0 auto; }
.legal-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.legal-back {
  background: none; border: none; color: var(--ink-soft); cursor: pointer;
  font-size: 13px; padding: 6px 10px; border-radius: var(--r-ctl);
}
.legal-back:hover { color: var(--ink); background: var(--desk-raised); }
.legal-stamp {
  display: inline-flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; border-radius: var(--r-seal);
  border: 1.6px dashed var(--accent); color: var(--accent-deep);
  font-size: 16px; transform: rotate(-8deg);
}
.legal-card { padding: 32px 30px 26px; }
.legal-head { border-bottom: 1px solid var(--ink-line); padding-bottom: 14px; margin-bottom: 18px; }
.legal-title { font-size: 24px; letter-spacing: 0.1em; color: var(--ink-on-paper); margin-bottom: 8px; }
.legal-meta { font-size: 12.5px; color: var(--ink-on-paper-faint); }
.legal-lead { font-size: 14px; line-height: 1.95; color: var(--ink-on-paper-soft); margin-bottom: 22px; }
.legal-sec { margin-bottom: 22px; }
.legal-sec h2 {
  font-size: 15.5px; font-weight: 700; color: var(--ink-on-paper);
  margin-bottom: 9px; letter-spacing: 0.04em;
}
.legal-sec p { font-size: 13.8px; line-height: 1.95; color: var(--ink-on-paper-soft); margin-bottom: 8px; }
.legal-sec ul { margin: 6px 0 8px 18px; }
.legal-sec li { font-size: 13.8px; line-height: 1.9; color: var(--ink-on-paper-soft); margin-bottom: 5px; }
.legal-sec code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12.5px; background: var(--accent-wash); color: var(--accent-text);
  padding: 1px 5px; border-radius: var(--r-chip);
}
.legal-sec strong, .legal-lead strong { color: var(--ink-on-paper); }
.legal-tip { font-size: 12.8px !important; color: var(--ink-on-paper-faint) !important; }
.legal-table-wrap { overflow-x: auto; margin: 8px 0 6px; }
.legal-table { width: 100%; border-collapse: collapse; font-size: 12.8px; }
.legal-table th {
  text-align: left; font-weight: 700; color: var(--ink-on-paper);
  border-bottom: 1.5px solid var(--ink-line); padding: 7px 9px; white-space: nowrap;
}
.legal-table td {
  color: var(--ink-on-paper-soft); padding: 7px 9px; vertical-align: top;
  border-bottom: 1px solid var(--ink-line); line-height: 1.7;
}
.legal-table .t-key { color: var(--ink-on-paper); font-weight: 600; white-space: nowrap; }
.legal-table .t-status { white-space: nowrap; }
.legal-table code { background: none; padding: 0; color: var(--ink-on-paper-soft); }
.legal-placeholder {
  border: 1px dashed var(--ink-line); border-radius: var(--r-ctl);
  padding: 10px 12px; color: var(--ink-on-paper-faint) !important;
}
.legal-foot {
  display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;
  border-top: 1px solid var(--ink-line); margin-top: 6px; padding-top: 14px;
  font-size: 12.5px; color: var(--ink-on-paper-faint);
}
.legal-link { color: var(--accent-text); text-decoration: none; }
.legal-link:hover { text-decoration: underline; }
</style>
