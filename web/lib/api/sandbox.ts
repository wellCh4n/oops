import { apiFetch } from "./client"
import { ApiResponse } from "./types"

export type SandboxInstanceStatus = "PENDING" | "RUNNING" | "FAILED" | "TERMINATING"

export interface SandboxInstance {
  id: string
  name: string
  environment: string
  image: string
  status: SandboxInstanceStatus
  createdBy: string | null
  createdByName: string | null
  createdAt: string | null
  cpuRequest: string
  cpuLimit: string
  memoryRequest: string
  memoryLimit: string
}

export interface SandboxInstanceCreatePayload {
  environment: string
  name?: string
  image: string
  cpu?: { request?: string; limit?: string }
  memory?: { request?: string; limit?: string }
  env?: Record<string, string>
}

export async function listSandboxImages(): Promise<ApiResponse<string[]>> {
  const response = await apiFetch(`/api/sandbox/images`)
  if (!response.ok) {
    throw new Error("Failed to fetch sandbox images")
  }
  return response.json() as Promise<ApiResponse<string[]>>
}

export async function listSandboxes(environment?: string, image?: string): Promise<ApiResponse<SandboxInstance[]>> {
  const params = new URLSearchParams()
  if (environment) params.set("environment", environment)
  if (image) params.set("image", image)
  const query = params.toString()
  const response = await apiFetch(`/api/sandbox/instances${query ? `?${query}` : ""}`)
  if (!response.ok) {
    throw new Error("Failed to fetch sandboxes")
  }
  return response.json() as Promise<ApiResponse<SandboxInstance[]>>
}

export async function getSandbox(id: string): Promise<ApiResponse<SandboxInstance>> {
  const response = await apiFetch(`/api/sandbox/instances/${id}`)
  if (!response.ok) {
    throw new Error("Failed to fetch sandbox")
  }
  return response.json() as Promise<ApiResponse<SandboxInstance>>
}

export async function createSandbox(payload: SandboxInstanceCreatePayload): Promise<ApiResponse<SandboxInstance>> {
  const response = await apiFetch(`/api/sandbox/instances`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    throw new Error("Failed to create sandbox")
  }
  return response.json() as Promise<ApiResponse<SandboxInstance>>
}

export async function deleteSandbox(id: string): Promise<void> {
  const response = await apiFetch(`/api/sandbox/instances/${id}`, { method: "DELETE" })
  if (!response.ok) {
    throw new Error("Failed to delete sandbox")
  }
}
