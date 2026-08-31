#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""知识库维基抓取（T1/T2）：
从中文维基百科抓取关系类条目的 wikitext，清洗为知识库 document/*.md 风格，
文件头带「来源 URL + CC BY-SA 4.0」署名（AGENTS.md 信任红线，可溯源）。

用法: python scripts/kb-fetch/fetch_wikipedia.py [--dir 输出目录] [--limit N]
许可: 中文维基 CC BY-SA 4.0（可商用，须署名 + 相同方式共享）。
"""
import json
import os
import re
import sys
import urllib.parse
import urllib.request

API = "https://zh.wikipedia.org/w/api.php"
UA = "lwx-kb-fetch/1.0 (knowledge base enrichment)"

# 关系/情感方法论相关的高价值中文维基条目（第一批；批次可扩展）
TITLES = [
    "依恋理论",
    "亲密关系",
    "爱情",
    "婚姻",
    "非暴力沟通",
    "冲突解决",
    "家庭暴力",
    "人际吸引",
    "爱情三角论",
    "离婚",
    "订婚",
    "恋爱",
]

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "document")


def fetch_wikitext(title):
    params = {
        "action": "parse",
        "page": title,
        "prop": "wikitext",
        "format": "json",
        "formatversion": "2",
    }
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    if "parse" not in data:
        return None
    return data["parse"]["wikitext"]


def clean(text):
    """清洗 wikitext: 去模板/脚注/链接语法/分类，保留正文结构。"""
    # 去掉 <ref>...</ref> 与 ref 单标签
    text = re.sub(r"<ref[^>/]*/>", "", text)
    text = re.sub(r"<ref[^>]*>.*?</ref>", "", text, flags=re.S)
    # 去模板 {{...}}（含嵌套，粗处理：多次去最内层）
    for _ in range(6):
        ntext = re.sub(r"\{\{[^{}]*\}\}", "", text)
        if ntext == text:
            break
        text = ntext
    # 去分类/语言链接/文件
    text = re.sub(r"\[\[(Category|分类):[^\]]*\]\]", "", text)
    text = re.sub(r"\[\[(File|文件|Image|图片):[^\]]*\]\]", "", text)
    # [[目标|显示]] → 显示；[[目标]] → 目标
    text = re.sub(r"\[\[[^\]|]*\|([^\]]*)\]\]", r"\1", text)
    text = re.sub(r"\[\[([^\]]*)\]\]", r"\1", text)
    # 去外部链接标记 [http... 显示] 与纯 [http]
    text = re.sub(r"\[(http[^\] ]*)( [^\]]*)?\]", r"\2", text)
    text = re.sub(r"https?://[^\s)\]】]+", "", text)
    # 去 HTML 注释
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    # 清理残留符号
    text = re.sub(r"[*]\s*([^\n])", r"- \1", text)  # 无序列表统一
    text = re.sub(r"={2,}([^=]+)={2,}", r"\n## \1\n", text)  # 标题
    # 去空行堆积
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def slug(title):
    return re.sub(r"[^\w\u4e00-\u9fff-]", "_", title)


def write_doc(title, body):
    url = "https://zh.wikipedia.org/wiki/" + urllib.parse.quote(title)
    header = (
        f"> 来源：维基百科《{title}》（{url}）\n"
        f"> 许可：CC BY-SA 4.0（可商用，须署名并保持相同许可）。本文为清洗后的知识库改写。\n\n"
    )
    out = header + "# " + title + "\n\n" + body + "\n"
    path = os.path.join(OUT_DIR, f"{slug(title)}.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write(out)
    return path


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    limit = int(sys.argv[sys.argv.index("--limit") + 1]) if "--limit" in sys.argv else len(TITLES)
    ok, fail = [], []
    for t in TITLES[:limit]:
        try:
            wt = fetch_wikitext(t)
            if wt is None:
                fail.append(t)
                continue
            body = clean(wt)
            if len(body) < 200:
                fail.append(t + "(过短)")
                continue
            p = write_doc(t, body)
            ok.append((t, len(body), p))
        except Exception as e:
            fail.append(f"{t}({e.__class__.__name__})")
    for t, n, p in ok:
        print(f"[OK] {t}  {n}字 -> {os.path.basename(p)}")
    if fail:
        print("[SKIP]", fail)


if __name__ == "__main__":
    main()