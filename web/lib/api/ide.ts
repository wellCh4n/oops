import { apiFetch } from "./client"
import { ApiResponse } from "./types"

export interface IDEInstance {
  name: string
  host: string
  https: boolean
}

export const listIDEs = async (namespace: string, application: string, env: string): Promise<ApiResponse<IDEInstance[]>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${application}/ide?env=${env}`)
  if (!response.ok) {
    throw new Error("Failed to fetch IDEs")
  }
  return response.json()
}

export const createIDE = async (namespace: string, application: string, env: string): Promise<ApiResponse<string>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${application}/ide?env=${env}`, {
    method: "POST",
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
