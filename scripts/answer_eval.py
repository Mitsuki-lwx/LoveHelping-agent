"""Answer Correctness 评测（LLM-as-judge，路线 B——不依赖 Langfuse 配置链）。

对 ground truth 每例：调 app /chat/sse 拿模型回答 → 用智谱 glm-4-flash 做 judge，
对照 golden_answer 评 0-1 分数 + 一句 reasoning（改编自 Langfuse Correctness 模板）。

用法：
  python scripts/answer_eval.py --base http://localhost:13263/api
  （智谱 key 自动从 src/main/resources/application-local.yml 读取，无需手动传）
"""
import argparse, io, json, os, re, time, urllib.request, urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
YML = os.path.join(ROOT, "src", "main", "resources", "application-local.yml")

def load_zhipu_key():
    """从 application-local.yml 的 app.ai.openai 段取智谱 key（本地开发明文）"""
    with io.open(YML, encoding="utf-8") as f:
        text = f.read()
    # 定位 openai: 段内首个 api-key
    m = re.search(r"openai:\s*\n(?:.*\n)*?\s+api-key:\s*([A-Za-z0-9._\-]+)", text)
    if not m:
        raise SystemExit("未在 application-local.yml 找到智谱 api-key")
    return m.group(1)

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

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:13263/api")
    ap.add_argument("--cases", default=os.path.join(ROOT, "scripts", "retrieval-ground-truth.json"))
    ap.add_argument("--api-key", default=None)
    ap.add_argument("--rounds", type=int, default=3)
    args = ap.parse_args()
    key = args.api_key or load_zhipu_key()
    gt = json.load(io.open(args.cases, encoding="utf-8"))["cases"]

    user = "acceval_%d" % int(time.time())
    req = urllib.request.Request(args.base + "/auth/register",
        data=json.dumps({"username": user, "password": "Passw0rd!123"}).encode(),
        headers={"Content-Type": "application/json"})
    token = json.loads(urllib.request.urlopen(req, timeout=30).read())["token"]

    # rounds 默认 3：flash 生成与 judge 均有波动，多轮均值才可信
    rounds = max(1, min(5, args.rounds))
    print("%-8s %-24s %-6s %s" % ("case", "各轮AC分(3轮)", "均值", "最差轮 reason"))
    means = []
    for c in gt:
        per = []
        worst = ("", 0.0)
        for rnd in range(rounds):
            answer = ask_app(args.base, token, c["question"], "ac_" + c["id"] + "_r" + str(rnd))
            if not answer:
                per.append(0.0)
                continue
            s, reason = judge(key, c["question"], c["golden_answer"], answer)
            per.append(s)
            if reason and (worst[1] == 0.0 or s < worst[1]):
                worst = (reason[:60], s)
            time.sleep(0.3)
        m = sum(per) / len(per)
        means.append(m)
        tag = "正确" if m > 0.7 else ("部分" if m >= 0.4 else "错误")
        print("%-8s %-24s %-6.2f %s" % (c["id"], " ".join("%.2f" % x for x in per), m,
              ("(全部正确)" if worst[0] == "" else worst[0])))
    if means:
        print("\nAnswer Correctness 均值(多轮): %.2f (n=%d, rounds=%d)" %
              (sum(means) / len(means), len(means), rounds))

if __name__ == "__main__":
    main()
