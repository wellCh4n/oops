import { apiFetch } from "./client"
import { ApiResponse } from "./types"

export interface User {
  id: string
  username: string
  email: string | null
  role: string
  createdTime?: string
}

export interface CreateUserRequest {
  username: string
  email: string
  password: string
}

export interface UpdateUserRequest {
  role: string
  email: string | null
  password?: string
}

export async function fetchUsers(): Promise<User[]> {
  const res = await apiFetch("/api/users")
  const data = await res.json() as ApiResponse<User[]>
  if (!data.success) {
    throw new Error(data.message || "Failed to fetch users")
  }
  return data.data
}

export async function createUser(request: CreateUserRequest): Promise<void> {
  const res = await apiFetch("/api/users", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  })
  const data = await res.json() as ApiResponse<boolean>
  if (!data.success) {
    throw new Error(data.message || "Failed to create user")
  }
}

export async function updateUser(userId: string, request: UpdateUserRequest): Promise<void> {
  const res = await apiFetch(`/api/users/${userId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  })
  const data = await res.json() as ApiResponse<boolean>
  if (!data.success) {
    throw new Error(data.message || "Failed to update user")
  }
}

export async function deleteUser(userId: string): Promise<void> {
  const res = await apiFetch(`/api/users/${userId}`, { method: "DELETE" })
  const data = await res.json() as ApiResponse<boolean>
  if (!data.success) {
    throw new Error(data.message || "Failed to delete user")
  }
}
