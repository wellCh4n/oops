import { apiFetch } from "./client"
import { ApiResponse } from "./types"

export type SandboxInstanceStatus = "PENDING" | "RUNNING" | "FAILED" | "TERMINATING"

export interface SandboxInstance {
  id: string
  name: string
  environment: string
  runtime: string
  image: string
  status: SandboxInstanceStatus
  createdBy: string | null
  createdAt: string | null
  cpuRequest: string
  cpuLimit: string
  memoryRequest: string
  memoryLimit: string
}

export interface SandboxInstanceCreatePayload {
  environment: string
  name?: string
  runtime?: string
  image?: string
  cpu?: { request?: string; limit?: string }
  memory?: { request?: string; limit?: string }
}

export interface SandboxRuntime {
  runtime: string
  image: string
}

export interface SandboxExecutionResult {
  exitCode: number
  output: string
}

export interface SandboxExecPayload {
  command: string
  timeoutSeconds?: number
  stream?: boolean
}

export async function listSandboxRuntimes(): Promise<ApiResponse<SandboxRuntime[]>> {
  const response = await apiFetch(`/api/sandbox/runtimes`)
  if (!response.ok) {
    throw new Error("Failed to fetch sandbox runtimes")
  }
  return response.json() as Promise<ApiResponse<SandboxRuntime[]>>
}

export async function listSandboxes(environment?: string, runtime?: string): Promise<ApiResponse<SandboxInstance[]>> {
  const params = new URLSearchParams()
  if (environment) params.set("environment", environment)
  if (runtime) params.set("runtime", runtime)
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

export async function execSandbox(id: string, payload: SandboxExecPayload): Promise<ApiResponse<SandboxExecutionResult>> {
  const response = await apiFetch(`/api/sandbox/instances/${id}/exec`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...payload, stream: false }),
  })
  if (!response.ok) {
    throw new Error("Failed to exec in sandbox")
  }
  return response.json() as Promise<ApiResponse<SandboxExecutionResult>>
}
