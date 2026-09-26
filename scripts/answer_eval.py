"""Answer Correctness 评测（LLM-as-judge，路线 B——不依赖 Langfuse 配置链）。

对 ground truth 每例：调 app /chat/sse 拿模型回答 → 用智谱 glm-4-flash 做 judge，
对照 golden_answer 评 0-1 分数 + 一句 reasoning（改编自 Langfuse Correctness 模板）。

用法：
  python scripts/answer_eval.py --base http://localhost:13263/api
  （智谱 key 优先级：环境变量 ZHIPU_API_KEY → 本地 gitignored 的 application-local.yml，无需手动传）
"""
import argparse, io, json, os, re, time, uuid, urllib.request, urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# ⛔ 2026-09-26 修复：原实现只看 `src/main/resources/application-local.yml`，而本仓凭据只存在于
# **构建产物** `target/classes/application-local.yml`（该文件在 .gitignore 里，src 下没有）
# → 脚本一进来就抛 FileNotFoundError，"答案质量从来没人跑过"的一半原因在这里。
# 现在按"环境变量 → target/classes → src/main/resources"顺序找，任一可用即可。
YML_CANDIDATES = [
    os.path.join(ROOT, "target", "classes", "application-local.yml"),
    os.path.join(ROOT, "src", "main", "resources", "application-local.yml"),
]


def load_zhipu_key():
    """取智谱 key：优先环境变量，其次本地 gitignored 的 application-local.yml"""
    env = os.environ.get("ZHIPU_API_KEY")
    if env:
        return env
    for path in YML_CANDIDATES:
        if not os.path.exists(path):
            continue
        with io.open(path, encoding="utf-8") as f:
            text = f.read()
        # 定位 openai: 段内首个 api-key
        m = re.search(r"openai:\s*\n(?:.*\n)*?\s+api-key:\s*([A-Za-z0-9._\-]+)", text)
        if m:
            return m.group(1)
    raise SystemExit("未找到智谱 api-key：可设 ZHIPU_API_KEY 环境变量，或准备 %s" % YML_CANDIDATES[0])

def judge(api_key, question, golden, answer):
    """调智谱 glm-4-flash 评 Answer Correctness（0-1 分数 + reasoning）"""
    prompt = f"""你是评测员。根据标准答案评价模型回答的正确性，输出 0-1 分数与一句话理由。

评分标准：
- 1 分：回答覆盖标准答案的全部关键事实，且没有与标准答案相悖或编造的内容；
- 中间值：缺少部分关键事实或有轻微偏差；
- 0 分：回答与标准答案关键事实相悖，或答非所问。

重要规则：**语义等价即视为命中**——模型用自己的话表达相同含义（措辞不同、例子不同但意思一致）应给高分，不得因用词差异扣分；与问题相关但超出标准答案范围的合理补充内容（扩展说明、额外例子）**不扣分**，除非与标准答案直接相悖或编造错误事实；只依据"关键事实点是否覆盖、是否相悖"评分。

题目：{question}
标准答案：{golden}
模型回答：{answer}

请严格按以下 JSON 格式输出（不要输出其他文字）：
{{"score": 0.0到1.0之间的数字, "reason": "一句话中文理由"}}"""
    body = json.dumps({
        "model": "glm-4-flash",
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.1,
        "response_format": {"type": "json_object"},
    }).encode()
    req = urllib.request.Request("https://open.bigmodel.cn/api/paas/v4/chat/completions",
        data=body, headers={"Content-Type": "application/json",
                            "Authorization": "Bearer " + api_key})
    for attempt in range(5):
        try:
            resp = json.loads(urllib.request.urlopen(req, timeout=180).read())
            content = resp["choices"][0]["message"]["content"]
            # 去可能的 ```json 包裹
            content = re.sub(r"^```(?:json)?|```$", "", content.strip()).strip()
            out = json.loads(content)
            return float(out.get("score", 0)), out.get("reason", "")
        except Exception as e:
            if attempt == 4:
                return 0.0, "judge调用失败: %s" % str(e)[:100]
            time.sleep(5)

def ask_app(base, token, question, cid):
    url = base + "/Love_app/chat/sse?" + urllib.parse.urlencode({"prompt": question, "chatId": cid})
    r = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    resp = urllib.request.urlopen(r, timeout=180)
    parts = []
    while True:
        line = resp.readline()
        if not line:
            break
        s = line.decode("utf-8", errors="replace").strip()
        if s.startswith("data:") and len(s) > 5:
            parts.append(s[5:].strip())
    return "".join(parts)


# ⛔ 2026-09-26 新增：**被拒/降级的文案要能认出来**，不能当"模型答错"打 0 分。
# 实测踩到：脚本用**固定 chatId** 配**每轮新用户** → 第二次起全部撞"跨用户抢注被拒"，
# 屏幕上就是"16 例全 0.00"，看起来像"模型全错"，其实是量具把自己跑坏了。
DENY_MARKERS = ("无权访问该会话", "无权访问", "AI 服务暂时不可用", "服务繁忙", "请稍后再试")


def looks_like_denial(text):
    t = (text or "").strip()
    return len(t) <= 20 and any(m in t for m in DENY_MARKERS)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:13263/api")
    ap.add_argument("--cases", default=os.path.join(ROOT, "scripts", "retrieval-ground-truth.json"))
    ap.add_argument("--api-key", default=None)
    ap.add_argument("--rounds", type=int, default=3)
    # ⛔ 2026-09-26 新增：**必须能把原始回答存下来**。
    # 首版只打印分数 → 出现"16 例全 0.00"时**无法归因**（是回答没取到？取到了但被 judge 判错？
    # 是应用回了错误文案？三种原因的处置完全相反）。凡是"打分型"量具，都要留存被评的原文。
    ap.add_argument("--out", default=None, help="把每例的原始回答+分数写成 JSON，便于事后归因")
    ap.add_argument("--limit", type=int, default=0, help="只跑前 N 例（>0 时），用于小规模标定量具")
    args = ap.parse_args()
    key = args.api_key or load_zhipu_key()
    gt = json.load(io.open(args.cases, encoding="utf-8"))["cases"]
    gt = [c for c in gt if c.get("golden_answer")]
    if args.limit > 0:
        gt = gt[:args.limit]

    user = "acceval_%d" % int(time.time())
    req = urllib.request.Request(args.base + "/auth/register",
        data=json.dumps({"username": user, "password": "Passw0rd!123"}).encode(),
        headers={"Content-Type": "application/json"})
    token = json.loads(urllib.request.urlopen(req, timeout=30).read())["token"]

    # rounds 默认 3：flash 生成与 judge 均有波动，多轮均值才可信
    rounds = max(1, min(5, args.rounds))
    # ⛔ chatId 必须**每轮运行都不同**：会话是有归属的（跨用户抢注会被拒 —— 那是安全特性，不是 bug）。
    # 原实现用固定 `ac_<id>_r<轮>` + 每轮新用户 → 第二次跑必然全部拿到"无权访问该会话"，
    # 而脚本会把它当 0 分记下来 → 看起来是"模型全错"。加上本次运行的随机后缀即可，安全特性不受影响。
    run_tag = uuid.uuid4().hex[:8]
    print("run_tag=%s（chatId 用 ac_<run_tag>_<case>_r<轮>，保证可重复运行）" % run_tag)
    print("%-8s %-24s %-6s %s" % ("case", "各轮AC分(3轮)", "均值", "最差轮 reason"))
    means = []
    report = []
    denied = 0
    for ci, c in enumerate(gt):
        per = []
        raw_rounds = []
        worst = ("", 0.0)
        for rnd in range(rounds):
            answer = ask_app(args.base, token, c["question"], "ac_%s_%s_r%d" % (run_tag, c["id"], rnd))
            if looks_like_denial(answer):
                denied += 1
                if ci == 0:
                    raise SystemExit(
                        "⛔ 量具坏了：第 1 例的回答是 %r —— 这是**被拒/降级**，不是模型答错。\n"
                        "   先查清原因（会话归属、限流、上游故障）再跑，否则整轮分数都是假的。" % answer)
            if not answer:
                per.append(0.0)
                raw_rounds.append({"round": rnd, "answer": "", "score": 0.0, "reason": "(回答为空)"})
                continue
            s, reason = judge(key, c["question"], c["golden_answer"], answer)
            per.append(s)
            raw_rounds.append({"round": rnd, "answer": answer, "score": s, "reason": reason})
            if reason and (worst[1] == 0.0 or s < worst[1]):
                worst = (reason[:60], s)
            time.sleep(0.3)
        m = sum(per) / len(per)
        means.append(m)
        tag = "正确" if m > 0.7 else ("部分" if m >= 0.4 else "错误")
        print("%-8s %-24s %-6.2f %s" % (c["id"], " ".join("%.2f" % x for x in per), m,
              ("(全部正确)" if worst[0] == "" else worst[0])))
        report.append({"id": c["id"], "question": c["question"], "golden": c["golden_answer"],
                       "mean": m, "tag": tag, "rounds": raw_rounds})
        if args.out:
            # 每例都落盘：中途崩了也有证据
            io.open(args.out, "w", encoding="utf-8").write(
                json.dumps({"run_tag": run_tag, "denied": denied, "n": len(report), "report": report},
                           ensure_ascii=False, indent=2))
    if means:
        print("\nAnswer Correctness 均值(多轮): %.2f (n=%d, rounds=%d%s)" %
              (sum(means) / len(means), len(means), rounds,
               ", 被拒/降级 %d 次" % denied if denied else ""))
    if args.out:
        print("已写入 %s" % args.out)

if __name__ == "__main__":
    main()
