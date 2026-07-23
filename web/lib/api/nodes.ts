import { ApiResponse, NodeStatus } from "./types"
import { apiFetch } from "./client"

export async function fetchNodes(env: string): Promise<ApiResponse<NodeStatus[]>> {
  const params = new URLSearchParams()
  params.set("env", env)

  const res = await apiFetch(`/api/nodes?${params.toString()}`)
  if (!res.ok) {
    throw new Error("Failed to fetch nodes")
  }
  return res.json() as Promise<ApiResponse<NodeStatus[]>>
}

export async function setNodeSchedulable(
  env: string,
  name: string,
  schedulable: boolean
): Promise<ApiResponse<boolean>> {
  const params = new URLSearchParams()
  params.set("env", env)
  params.set("schedulable", String(schedulable))

  const res = await apiFetch(`/api/nodes/${encodeURIComponent(name)}/schedulable?${params.toString()}`, {
    method: "POST",
  })
  if (!res.ok) {
    throw new Error("Failed to update node scheduling")
  }
  return res.json() as Promise<ApiResponse<boolean>>
}

