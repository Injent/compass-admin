export async function api(url, options = {}) {
  const headers = new Headers(options.headers)
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const response = await fetch(url, { ...options, headers, credentials: 'same-origin' })

  if (response.status === 401) {
    location.assign(`/auth?next=${encodeURIComponent(location.pathname + location.search)}`)
    throw new Error('Требуется авторизация')
  }

  if (response.status === 204) return null
  const result = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(result.error || 'Не удалось выполнить запрос')
  return result
}
