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
