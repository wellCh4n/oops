import { ApiResponse, NodeStatus } from "./types"
import { apiFetch } from "./client"

export async function fetchNodes(env: string): Promise<ApiResponse<NodeStatus[]>> {
  const params = new URLSearchParams()
  params.set("env", env)

  const res = await apiFetch(`/api/nodes?${params.toString()}`)
  if (!res.ok) {
    throw new Error("Failed to fetch nodes")
  }
  return res.json()
}

