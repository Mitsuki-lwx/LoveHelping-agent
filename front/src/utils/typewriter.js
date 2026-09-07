/**
 * 打字机动画工具（2026-09-07 重构抽取：原 LoveChat/ManusChat 各维护一份 typewrite+busyTimer）。
 * 用法：
 *   const tw = createTypewriter()                // 页面生命周期创建一次
 *   tw.start(text, (partial) => { target.content = partial })  // 逐字回写；再次 start 自动停止上一轮
 *   tw.stop()                                    // 组件卸载时清理定时器
 */
export function createTypewriter({ intervalMs = 35, chunk = 1 } = {}) {
  let timer = null

  const stop = () => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  /**
   * 开始逐字播放 text。onTick 每步收到当前已呈现的完整前缀（text.slice(0, i)）。
   * 播放到结尾自动 stop；播放中途再次调用 start 会先停掉上一轮。
   */
  const start = (text, onTick) => {
    stop()
    if (!text) {
      onTick('')
      return
    }
    let i = 0
    timer = setInterval(() => {
      i = Math.min(i + chunk, text.length)
      onTick(text.slice(0, i))
      if (i >= text.length) {
        stop()
      }
    }, intervalMs)
  }

  return { start, stop }
}

export default createTypewriter
