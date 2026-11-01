# 公网 demo 上线手册

> 目标：让外人**点一下就能用**，同时不让你的 LLM 额度被刷爆、不把内部端点暴露出去。
> 本目录只放操作步骤与检查项，不含任何密钥。

## 0. 先做决定（这两件事只有你能定）

| 决定 | 选项 | 影响 |
| --- | --- | --- |
| 跑在哪 | ① 本机 + Cloudflare Tunnel ② VPS + Caddy | ① 不用买服务器，关机即下线；② 稳定但要维护 |
| 注册开不开 | 开放注册 / 只发一个体验账号 | 开放注册 = 任何人可注册 = 额度与合规风险 |

**建议**：先用 ①+「只发一个体验账号」，跑一周看真实使用，再决定要不要上 VPS。

## 1. 方案 A：本机 + Cloudflare Tunnel（无公网 IP，最快）

```bash
# 一次性
winget install --id Cloudflare.cloudflared
cloudflared tunnel login          # 浏览器授权，选你的域名

# 每次起 demo
docker compose up -d              # MySQL / PG / Redis / app / mcp-server
cloudflared tunnel --url http://localhost:8088
# → 输出一个 https://xxx.trycloudflare.com，发给别人即可
```

临时域名每次重启都会变；要固定域名就 `cloudflared tunnel create lovehelping` 再绑 DNS。

## 2. 方案 B：VPS + Caddy（固定域名 + 自动 HTTPS）

```caddyfile
love.example.com {
    reverse_proxy 127.0.0.1:8088 {
        flush_interval -1          # ⛔ SSE 必须关缓冲，否则流式变成"一次性蹦出来"
        transport http {
            read_timeout 300s
        }
    }
}
```

`docker-compose.yml` 里 app 只绑 `127.0.0.1:8088`，不要 `0.0.0.0`——让 Caddy 做唯一入口。

## 3. 上线前必查（跑 `scripts/demo_preflight.py`）

```bash
python scripts/demo_preflight.py --base https://love.example.com/api
```

它会逐项核对：

- [ ] `/actuator/health` = UP
- [ ] 登录失败第 6 次返回 429（证明节流是 ENFORCE，不是 shadow/off）
- [ ] 管理端点无凭据 → 403（不是 200，也不是 500）
- [ ] `/actuator/env`、`/actuator/beans` 不可匿名读
- [ ] 站点是 **https**（明文 HTTP 下不要发 demo 链接）
- [ ] 每日配额已设置（`DAILY_QUOTA`），不是无限

## 4. 成本与滥用控制（按重要性）

1. **每日配额**：`DAILY_QUOTA=20`（demo 不需要 100）。用完后用户看到的是 `429 明日再试`。
2. **注册**：若只发体验账号，就把注册页隐藏/拦截；开放注册则必须盯着注册量。
3. **Langfuse**：demo 环境建议 `LANGFUSE_ENABLED=false`，避免把外部访客的 trace 发到你本地实例。
4. **日志**：确认生产日志不含正文（`content_hmac` 已实现，但 demo 若用 `local` profile 会打 DEBUG——务必用 `prod`）。

## 5. 合规（C 端情感产品，公开可访问就会有人查）

- 隐私政策 / 用户协议页面必须可点开（已有路由）
- 免责声明常驻（"AI 建议 ≠ 专业心理咨询"）
- 未成年人模式服务端强制（已有：宵禁 4004 / 超限 4005）
- 危机转介话术必须带真实热线
- **不要**在 demo 里开放"诊断/人格报告"类入口——这些本来就没实现，别用文案暗示有

## 6. 下线

```bash
cloudflared tunnel cleanup   # 方案 A
docker compose down          # 数据保留；加 -v 才删卷
```

## 附：这个 demo 的诚实定位

它是**功能演示**，不是生产服务：单实例、无多活、无 SLA。
面试时可以这么说，比吹"生产级"安全得多。
