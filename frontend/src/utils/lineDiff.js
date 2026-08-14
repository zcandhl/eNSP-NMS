/**
 * 基于 LCS 的行级 diff；可过滤华为配置噪声行。
 */
export function normalizeConfigLine(line = '') {
  return String(line).replace(/\r$/, '')
}

export function isNoiseConfigLine(line = '') {
  const s = normalizeConfigLine(line).trim()
  if (!s) return true
  if (s.startsWith('#')) return true
  if (s.startsWith('!')) return true
  // 时间戳 / 保存信息
  if (/^!Time:/i.test(s) || /^!.*?Time:/i.test(s)) return true
  if (/Last configuration was saved/i.test(s)) return true
  if (/^!Last /i.test(s)) return true
  // display 回显与标题
  if (/^display\s+(current|saved)-configuration/i.test(s)) return true
  if (/Building configuration/i.test(s)) return true
  if (/Current configuration/i.test(s)) return true
  if (/Saved configuration/i.test(s)) return true
  if (/^Info:/i.test(s) || /^Warning:/i.test(s)) return true
  // 提示符残留
  if (/^\[.*]$/.test(s) || /^<.*>$/.test(s)) return true
  if (/^[A-Za-z0-9._-]+[>#]\s*$/.test(s)) return true
  // 分页 / 分隔
  if (/^----+$/.test(s) || /^More$/i.test(s)) return true
  if (/^return$/i.test(s)) return true
  return false
}

export function stripNoise(text = '', enabled = true) {
  if (!enabled) return String(text)
  return String(text)
    .split('\n')
    .filter((l) => !isNoiseConfigLine(l))
    .join('\n')
}

export function diffLines(sourceText = '', targetText = '', options = {}) {
  const ignoreNoise = options.ignoreNoise !== false
  const a = stripNoise(sourceText, ignoreNoise).split('\n').map(normalizeConfigLine)
  const b = stripNoise(targetText, ignoreNoise).split('\n').map(normalizeConfigLine)
  const n = a.length
  const m = b.length
  const dp = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      if (a[i] === b[j]) dp[i][j] = dp[i + 1][j + 1] + 1
      else dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }

  const lines = []
  let added = 0
  let removed = 0
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      lines.push({ type: 'unchanged', text: a[i], left: a[i], right: b[j] })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      lines.push({ type: 'removed', text: a[i], left: a[i], right: '' })
      removed++
      i++
    } else {
      lines.push({ type: 'added', text: b[j], left: '', right: b[j] })
      added++
      j++
    }
  }
  while (i < n) {
    lines.push({ type: 'removed', text: a[i], left: a[i], right: '' })
    removed++
    i++
  }
  while (j < m) {
    lines.push({ type: 'added', text: b[j], left: '', right: b[j] })
    added++
    j++
  }

  return { added, removed, modified: 0, lines }
}

/** 导出 unified diff 文本 */
export function toUnifiedDiff(diffResult, leftLabel = 'left', rightLabel = 'right') {
  const lines = diffResult?.lines || []
  const out = [
    `--- ${leftLabel}`,
    `+++ ${rightLabel}`
  ]
  for (const line of lines) {
    if (line.type === 'unchanged') {
      out.push(` ${line.left ?? line.text ?? ''}`)
    } else if (line.type === 'removed') {
      out.push(`-${line.left ?? line.text ?? ''}`)
    } else if (line.type === 'added') {
      out.push(`+${line.right ?? line.text ?? ''}`)
    }
  }
  out.push('')
  out.push(`# summary: +${diffResult?.added || 0} -${diffResult?.removed || 0}`)
  return out.join('\n')
}
