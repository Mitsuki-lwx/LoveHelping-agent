<template>
  <div class="legal-page">
    <div class="legal-inner">
      <div class="legal-top">
        <button class="legal-back" @click="$router.back()">← 返回</button>
        <span class="legal-stamp hand">约</span>
      </div>

      <article class="letter-card fold legal-card">
        <header class="legal-head">
          <h1 class="hand legal-title">用户服务协议</h1>
          <p class="legal-meta">生效日期：{{ EFFECTIVE_DATE }} · 适用于「恋爱解忧所」全部功能</p>
        </header>

        <p class="legal-lead">
          这份协议说的是：这个服务是什么、AI 生成的内容如何标识、你的数据怎么处理、遇到问题去哪里说。
          注册前请完整读一遍 —— 勾选同意即表示你已理解以下全部条款。
        </p>

        <section class="legal-sec">
          <h2>一、服务内容</h2>
          <p>
            「恋爱解忧所」是一个<strong>情感陪伴类对话服务</strong>：你可以写下一段心事，由 AI 生成一段回信；
            服务还会记住你主动提供的记忆片段与关系档案，让后续回复更贴合你的处境。
          </p>
          <p class="legal-warn">
            ⛔ 本服务<strong>不是专业心理治疗、心理咨询或医疗诊断</strong>，不能替代执业医师或心理咨询师。
            若你正经历持续的情绪困扰，请寻求线下专业帮助。
          </p>
        </section>

        <section class="legal-sec">
          <h2>二、AI 生成内容标识</h2>
          <p>
            本服务<strong>全部对话回复均由人工智能生成</strong>，不是真人客服撰写。我们按《人工智能生成合成内容标识办法》
            的要求做<strong>显式标识</strong>，具体方式如下：
          </p>
          <ul>
            <li><strong>聊天气泡</strong>：每条 AI 回复的发送者名称旁都有「{{ AI_LABEL }}」小标签，标签常驻显示、不随滚动消失。</li>
            <li><strong>数据导出</strong>：你导出的 JSON 文件顶层带有 <code>aiGenerated: true</code> 与
              <code>aiGeneratedNote</code> 字段，说明该文件中的对话回复由 AI 生成。</li>
            <li><strong>本协议与隐私政策</strong>：同样载明上述标识方式与样式。</li>
          </ul>
          <p>请不要把 AI 生成的回复当作真人意见或专业建议转发给他人。</p>
        </section>

        <section class="legal-sec">
          <h2>三、账号与使用资格</h2>
          <ul>
            <li>注册需提供用户名、密码与<strong>出生年份</strong>；出生年份用于判断是否为你开启未成年人模式。</li>
            <li>请妥善保管密码。忘记密码时，可通过你绑定的邮箱接收验证码重置。</li>
            <li>账号仅限本人使用，不得出租、出借或转让。</li>
          </ul>
        </section>

        <section class="legal-sec">
          <h2>四、未成年人使用</h2>
          <ul>
            <li>未满 18 周岁的用户，<strong>须在监护人同意并指导下使用</strong>本服务。</li>
            <li>我们为未成年账号默认开启<strong>未成年人模式</strong>：每日对话条数上限 {{ MINOR.dailyLimit }} 条，
              {{ MINOR.quietStart }}–{{ MINOR.quietEnd }} 为休息时段，期间不提供对话。</li>
            <li>未成年人模式开启后，聊天页顶部会持续显示一条提示，告知当天的限制。</li>
            <li>监护人如发现未成年人使用不当，可通过本协议第九条的联系方式联系我们，我们会配合处理。</li>
          </ul>
        </section>

        <section class="legal-sec">
          <h2>五、内容与行为规范</h2>
          <p>你不得利用本服务发布、上传或传播下列内容：</p>
          <ul>
            <li>违反法律法规、危害国家安全与社会公共利益的；</li>
            <li>侮辱、诽谤、骚扰他人，或侵犯他人隐私、名誉、知识产权等合法权益的；</li>
            <li>涉及他人真实身份信息、聊天记录等未经授权的个人信息；</li>
            <li>诱导 AI 生成违法有害内容，或试图绕过内容安全机制的。</li>
          </ul>
          <p>违反上述规范的，我们可限制功能直至注销账号，并依法配合有权机关。</p>
        </section>

        <section class="legal-sec">
          <h2>六、AI 的能力边界与安全处置</h2>
          <p>
            对话由大模型生成，可能出现不准确、不完整或与你实际情况不符的内容，请自行判断。
            当你发送的内容中出现<strong>自伤、自杀等风险信号</strong>时，系统的动作是
            <strong>转介</strong>：暂停常规回复，改为向你<strong>推送心理援助热线等求助渠道</strong>，
            并提示你联系身边可信任的人。
          </p>
          <p class="legal-warn">
            ⛔ 转介不是治疗。我们不做诊断、不评估病情、不提供用药建议 —— 这是刻意的边界，而不是功能缺失。
          </p>
        </section>

        <section class="legal-sec">
          <h2>七、投诉举报</h2>
          <p>
            你可以在「我的小窝 → 投诉举报」进入举报页面，选择分类（内容违规 / 隐私泄露 / 辱骂骚扰 / 其他问题）、
            填写具体情况，可选留联系方式以便我们回复。
          </p>
          <p class="legal-strong">
            处理流程与时限：我们<strong>自受理之日起 {{ FEEDBACK_DAYS }} 个工作日内</strong>完成核查并向你反馈处理结果；
            需要更长时间的，会先告知你原因与预计时间。举报记录可在同一页面「我的举报」中查看状态
            （待处理 / 处理中 / 已完结）。
          </p>
        </section>

        <section class="legal-sec">
          <h2>八、协议变更</h2>
          <p>
            服务调整或法律要求变化时，我们可能更新本协议；更新后会修改页面顶部的生效日期。
            涉及你权利的重要变更，会在应用内显著提示。继续使用即视为接受更新后的协议。
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
          <router-link to="/privacy" class="legal-link">阅读《隐私政策》→</router-link>
        </footer>
      </article>
    </div>
  </div>
</template>

<script setup>
/**
 * 用户服务协议（2026-10-13 合规改造新增）。
 *
 * ⛔ 这一页是**监管与用户都会真的去读**的东西，所以内容必须与实现一致：
 *   · 「AI 生成」标识 → 聊天气泡上的 .ai-tag 与导出 JSON 的 aiGenerated 字段（同一文案常量）；
 *   · 「15 个工作日」→ 与投诉举报页的时限说明取同一个常量；
 *   · 「转介」→ 后端自伤信号处置的真实动作（推送援助热线），不是"治疗"。
 * 所有会随实现变化的值都从 utils/legal.js 取，页面里不写第二份。
 */
import {
  LEGAL_EFFECTIVE_DATE as EFFECTIVE_DATE,
  LEGAL_CONTACT as contact,
  COMPLAINT_FEEDBACK_WORKING_DAYS as FEEDBACK_DAYS,
  MINOR_MODE as MINOR,
  AI_LABEL_TEXT as AI_LABEL,
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
.legal-warn {
  border-left: 3px solid var(--accent);
  background: var(--accent-wash);
  padding: 10px 12px;
  border-radius: 0 var(--r-ctl) var(--r-ctl) 0;
  color: var(--ink-on-paper) !important;
}
.legal-strong { color: var(--ink-on-paper) !important; }
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
