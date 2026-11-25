/**
 * @file    blobResponse.js
 * @module  工具函数
 * @desc    Blob 下载响应校验与文件名提取。
 *
 * @author  ZC
 * @date    2026-08-15 * @version 1.0.0
 */

export async function ensureBlobFile(blob, fallbackName = 'download') {
  if (!(blob instanceof Blob)) {
    if (blob == null) throw new Error(`${fallbackName} 为空`)
    return blob
  }
  if (blob.size === 0) {
    throw new Error(`${fallbackName} 为空`)
  }

  const type = (blob.type || '').toLowerCase()
  // ZIP / 明确二进制：直接返回，禁止text() 解码
  if (
    type.includes('zip')
    || type.includes('octet-stream')
    || type.includes('application/x-zip')
  ) {
    return blob
  }

  // 仅对JSON或「很小且像错误JSON」的响应做探测
  const shouldProbe = type.includes('json') || (blob.size > 0 && blob.size < 2048 && !type.includes('text'))
  if (!shouldProbe && type.includes('text')) {
    return blob
  }
  if (!shouldProbe) {
    // 用文件头判断：ZIP以PK\x03\x04开头
    const head = new Uint8Array(await blob.slice(0, 4).arrayBuffer())
    if (head[0] === 0x50 && head[1] === 0x4b) {
      return blob
    }
    // 非ZIP且非小体积，直接返回
    if (blob.size >= 2048) {
      return blob
    }
  }

  const buf = await blob.slice(0, Math.min(blob.size, 512)).arrayBuffer()
  const bytes = new Uint8Array(buf)
  // ZIP magic
  if (bytes[0] === 0x50 && bytes[1] === 0x4b) {
    return blob
  }
  // 以 { 或 [ 开头才当JSON错误
  let i = 0
  while (i < bytes.length && (bytes[i] === 0x20 || bytes[i] === 0x0a || bytes[i] === 0x0d || bytes[i] === 0x09)) i++
  if (bytes[i] === 0x7b || bytes[i] === 0x5b) {
    const text = await blob.text()
    let msg = '下载失败'
    try {
      const json = JSON.parse(text.trim())
      msg = json.message || json.error || msg
    } catch {
      msg = text.trim().slice(0, 200) || msg
    }
    throw new Error(msg)
  }

  return blob
}
