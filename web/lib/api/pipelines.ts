import { apiFetch } from "./client"
import { watchSse, SseEventHandlers } from "./sse"
import { ApiResponse, Pipeline, PipelineLogBatch, PipelineStepsSnapshot, Page } from "./types"

export const getPipelines = async (
  namespace: string,
  name: string,
  environment?: string,
  page?: number,
  size?: number
): Promise<ApiResponse<Page<Pipeline>>> => {
  const params = new URLSearchParams()
  if (environment && environment !== "all") params.set("environment", environment)
  if (page !== undefined) params.set("page", String(page))
  if (size !== undefined) params.set("size", String(size))

  const qs = params.toString()
  const url = `/api/namespaces/${namespace}/applications/${name}/pipelines${qs ? `?${qs}` : ""}`
  const response = await apiFetch(url)
  if (!response.ok) {
    throw new Error("Failed to fetch pipelines")
  }
  return response.json() as Promise<ApiResponse<Page<Pipeline>>>
}

export const getPipeline = async (namespace: string, name: string, id: string): Promise<ApiResponse<Pipeline>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/pipelines/${id}`)
  if (!response.ok) {
    throw new Error("Failed to fetch pipeline")
  }
  return response.json() as Promise<ApiResponse<Pipeline>>
}

export const stopPipeline = async (namespace: string, name: string, id: string): Promise<ApiResponse<boolean>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/pipelines/${id}/stop`, {
    method: "PUT",
  })
  if (!response.ok) {
    throw new Error("Failed to stop pipeline")
  }
  return response.json() as Promise<ApiResponse<boolean>>
}

export const deployPipeline = async (namespace: string, name: string, id: string): Promise<ApiResponse<boolean>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/pipelines/${id}/deploy`, {
    method: "PUT",
  })
  if (!response.ok) {
    throw new Error("Failed to deploy pipeline")
  }
  return response.json() as Promise<ApiResponse<boolean>>
}

// Rolls back to a historic successful pipeline by deploying its image again.
// Returns the id of the newly created rollback pipeline.
export const rollbackPipeline = async (namespace: string, name: string, id: string): Promise<ApiResponse<string>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/pipelines/${id}/rollback`, {
    method: "POST",
  })
  if (!response.ok) {
    throw new Error("Failed to rollback pipeline")
  }
  return response.json() as Promise<ApiResponse<string>>
}

// Returns the container image currently running on the application's StatefulSet for the given environment.
export const getCurrentImage = async (namespace: string, name: string, environment: string): Promise<ApiResponse<string>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/current-image?environment=${encodeURIComponent(environment)}`)
  if (!response.ok) {
    throw new Error("Failed to fetch current image")
  }
  return response.json() as Promise<ApiResponse<string>>
}

interface PipelineStreamHandlers {
  // A message from the server saying why there is nothing more to show (the job was cleaned up…).
  onError: (message: string) => void
  // The stream ran to its natural end; the source is already closed.
  onEnd?: () => void
  // The connection could not be kept up; the source is already closed.
  onTerminate?: () => void
}

export interface PipelineStepsHandlers extends PipelineStreamHandlers {
  onSteps: (names: string[]) => void
  onStatus: (snapshot: PipelineStepsSnapshot) => void
}

export interface PipelineStepLogHandlers extends PipelineStreamHandlers {
  onLog: (batch: PipelineLogBatch) => void
}

// Both streams end with an "end" event. A browser EventSource reconnects on its own after the
// server closes a response — that is what makes it resilient — so an ended stream has to be closed
// from here, or a finished step's log would be fetched again every few seconds forever.
function watchUntilEnd<TEventMap extends Record<string, unknown>>(
  url: string,
  events: Omit<SseEventHandlers<TEventMap>, "end">,
  handlers: PipelineStreamHandlers
): () => void {
  let close = () => {}
  close = watchSse<TEventMap & { end: unknown }>({
    url,
    events: {
      ...events,
      end: () => {
        close()
        handlers.onEnd?.()
      },
    } as SseEventHandlers<TEventMap & { end: unknown }>,
    onTerminate: handlers.onTerminate,
  })
  return close
}

// Follows the build's steps: which containers there are, and where each one stands, until the pod
// finishes. Cheap enough to keep open for a whole build — it carries no log lines.
export function watchPipelineSteps(
  namespace: string,
  name: string,
  id: string,
  handlers: PipelineStepsHandlers
): () => void {
  return watchUntilEnd<{ steps: string[]; status: PipelineStepsSnapshot; error: string }>(
    `/api/namespaces/${namespace}/applications/${name}/pipelines/${id}/steps/watch`,
    {
      steps: handlers.onSteps,
      status: handlers.onStatus,
      error: handlers.onError,
    },
    handlers
  )
}

// Streams one step's log. A finished step replays and ends at once; a running one is followed
// until it terminates. Resuming after a dropped connection is the browser's Last-Event-ID.
export function streamPipelineStepLog(
  namespace: string,
  name: string,
  id: string,
  container: string,
  handlers: PipelineStepLogHandlers
): () => void {
  return watchUntilEnd<{ log: PipelineLogBatch; error: string }>(
    `/api/namespaces/${namespace}/applications/${name}/pipelines/${id}/log?container=${encodeURIComponent(container)}`,
    {
      log: handlers.onLog,
      error: handlers.onError,
    },
    handlers
  )
}
