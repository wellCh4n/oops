import { apiFetch } from "./client"
import { watchSseUntilEnd, SseEndingStreamHandlers } from "./sse"
import { ApiResponse, Pipeline, LogBatch, PipelineStepsSnapshot, Page } from "./types"

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

/**
 * The pipeline list page. Both the namespace and the application take "all" as a wildcard, so
 * an empty `app` lists the whole scope through the same per-application endpoint.
 */
export const getPipelinesInScope = async (
  namespace: string,
  filters: { app?: string; environment?: string; mine?: boolean },
  page?: number,
  size?: number
): Promise<ApiResponse<Page<Pipeline>>> => {
  const params = new URLSearchParams()
  if (filters.environment && filters.environment !== "all") params.set("environment", filters.environment)
  if (filters.mine) params.set("mine", "true")
  if (page !== undefined) params.set("page", String(page))
  if (size !== undefined) params.set("size", String(size))

  const qs = params.toString()
  const application = filters.app || "all"
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${application}/pipelines${qs ? `?${qs}` : ""}`)
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

interface PipelineStreamHandlers extends SseEndingStreamHandlers {
  // A message from the server saying why there is nothing more to show (the job was cleaned up…).
  onError: (message: string) => void
}

export interface PipelineStepsHandlers extends PipelineStreamHandlers {
  onSteps: (names: string[]) => void
  onStatus: (snapshot: PipelineStepsSnapshot) => void
}

export interface PipelineStepLogHandlers extends PipelineStreamHandlers {
  onLog: (batch: LogBatch) => void
}

// Follows the build's steps: which containers there are, and where each one stands, until the pod
// finishes. Cheap enough to keep open for a whole build — it carries no log lines.
export function watchPipelineSteps(
  namespace: string,
  name: string,
  id: string,
  handlers: PipelineStepsHandlers
): () => void {
  return watchSseUntilEnd<{ steps: string[]; status: PipelineStepsSnapshot; error: string }>(
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
  return watchSseUntilEnd<{ log: LogBatch; error: string }>(
    `/api/namespaces/${namespace}/applications/${name}/pipelines/${id}/log?container=${encodeURIComponent(container)}`,
    {
      log: handlers.onLog,
      error: handlers.onError,
    },
    handlers
  )
}
