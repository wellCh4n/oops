import { API_BASE_URL } from "./config"

const DEFAULT_MAX_CONSECUTIVE_FAILURES = 10

export type SseEventHandlers<TEventMap> = {
  [K in keyof TEventMap]: (data: TEventMap[K]) => void
}

export interface SseWatchOptions<TEventMap extends Record<string, unknown>> {
  // Absolute URL or path. Relative paths are prefixed with API_BASE_URL.
  url: string
  // One handler per named SSE event. Event payloads are JSON-parsed.
  events: SseEventHandlers<TEventMap>
  // Per-failure callback, fired on every onerror except the one that
  // triggers onTerminate. Browser will auto-reconnect in this case.
  onError?: (event: Event) => void
  // Fired once after `maxConsecutiveFailures` consecutive errors with no
  // successful onopen in between. The EventSource is already closed.
  onTerminate?: () => void
  // Defaults to 10. With the browser's 3s reconnect interval this is ~30s
  // before we give up.
  maxConsecutiveFailures?: number
}

export function watchSse<TEventMap extends Record<string, unknown>>(
  options: SseWatchOptions<TEventMap>
): () => void {
  const fullUrl = options.url.startsWith("http")
    ? options.url
    : `${API_BASE_URL}${options.url}`
  const maxFailures = options.maxConsecutiveFailures ?? DEFAULT_MAX_CONSECUTIVE_FAILURES

  const eventSource = new EventSource(fullUrl, { withCredentials: true })
  let failureCount = 0

  eventSource.onopen = () => {
    failureCount = 0
  }

  for (const [eventName, handler] of Object.entries(options.events)) {
    eventSource.addEventListener(eventName, (event) => {
      try {
        const raw = (event as MessageEvent<string>).data
        const parsed = JSON.parse(raw) as TEventMap[keyof TEventMap]
        ;(handler as (data: TEventMap[keyof TEventMap]) => void)(parsed)
      } catch (error) {
        console.error(`Failed to parse SSE event "${eventName}":`, error)
      }
    })
  }

  eventSource.onerror = (event) => {
    failureCount += 1
    if (failureCount >= maxFailures) {
      eventSource.close()
      options.onTerminate?.()
      return
    }
    options.onError?.(event)
  }

  return () => eventSource.close()
}
