/**
 * 页面可见时运行轮询，切到后台标签页时暂停。
 * @param {() => void | Promise<void>} tick
 * @param {number} intervalMs
 * @returns {{ start: () => void, stop: () => void }}
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
