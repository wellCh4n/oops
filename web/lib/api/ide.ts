import { apiFetch } from "./client"
import { ApiResponse } from "./types"

export interface IDEInstance {
  id: string
  name: string
  host: string
  https: boolean
  createdAt: string | null
  ready: boolean
}

export interface IDEDefaultConfig {
  settings: string
  env: string
  extensions: string
}

export interface IDECreatePayload {
  name?: string
  branch?: string
  settings: string
  env: string
  extensions: string
}

export const listIDEs = async (namespace: string, application: string, env: string): Promise<ApiResponse<IDEInstance[]>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${application}/ide?env=${env}`)
  if (!response.ok) {
    throw new Error("Failed to fetch IDEs")
  }
  return response.json()
}

export const getDefaultIDEConfig = async (namespace: string, application: string, env: string): Promise<ApiResponse<IDEDefaultConfig>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${application}/ide/config/default?env=${env}`)
  if (!response.ok) {
    throw new Error("Failed to fetch default IDE config")
  }
  return response.json()
}

export const createIDE = async (namespace: string, application: string, env: string, payload: IDECreatePayload): Promise<ApiResponse<string>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${application}/ide?env=${env}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    throw new Error("Failed to create IDE")
  }
  return response.json()
}

export const deleteIDE = async (namespace: string, application: string, name: string, env: string): Promise<void> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${application}/ide/${name}?env=${env}`, {
    method: "DELETE",
  })
  if (!response.ok) {
    throw new Error("Failed to delete IDE")
  }
}
