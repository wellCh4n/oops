import { apiFetch } from "./client"
import { ApiResponse, Pipeline, Page } from "./types"

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
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/current-image?env=${encodeURIComponent(environment)}`)
  if (!response.ok) {
    throw new Error("Failed to fetch current image")
  }
  return response.json() as Promise<ApiResponse<string>>
}
