export function normalizeDetailsUrl(value) {
  const link = value.trim()
  if (!link) return ''
  if (/\s/.test(link)) return null
  const normalized = link.startsWith('//') ? `https:${link}` : link.includes('://') ? link : `https://${link}`
  try {
    const url = new URL(normalized)
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password && (url.hostname.includes('.') || url.hostname === 'localhost')
      ? normalized : null
  } catch { return null }
}
