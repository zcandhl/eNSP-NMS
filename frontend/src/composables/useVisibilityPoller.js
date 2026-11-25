/**
 * @file    useVisibilityPoller.js
 * @module  组合式逻辑
 * @desc    页面可见性感知的轮询定时器。
 *
 * @author  ZC
 * @date    2026-08-15 * @version 1.0.0
 */

export function createVisibilityPoller(tick, intervalMs) {
  let timerId = null
  let running = false

  const onVisibility = () => {
    if (document.hidden) {
      clearTimer()
    } else if (running) {
      void Promise.resolve(tick())
      schedule()
    }
  }

  const clearTimer = () => {
    if (timerId != null) {
      clearInterval(timerId)
      timerId = null
    }
  }

  const schedule = () => {
    clearTimer()
    timerId = setInterval(() => {
      if (!document.hidden) {
        void Promise.resolve(tick())
      }
    }, intervalMs)
  }

  const start = () => {
    running = true
    document.addEventListener('visibilitychange', onVisibility)
    if (!document.hidden) {
      schedule()
    }
  }

  const stop = () => {
    running = false
    document.removeEventListener('visibilitychange', onVisibility)
    clearTimer()
  }

  return { start, stop }
}
