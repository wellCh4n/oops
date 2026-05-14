import { apiFetch } from "./client"
import { ApiResponse, Page } from "./types"

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

export async function fetchUsersPage(keyword?: string, page = 1, size = 10): Promise<Page<User>> {
  const params = new URLSearchParams()
  if (keyword) params.set("keyword", keyword)
  params.set("page", String(page))
  params.set("size", String(size))
  const res = await apiFetch(`/api/users/page?${params.toString()}`)
  const data = await res.json() as ApiResponse<Page<User>>
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

export async function updateMyProfile(email: string | null): Promise<void> {
  const res = await apiFetch("/api/users/me", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email }),
  })
  const data = await res.json() as ApiResponse<boolean>
  if (!data.success) {
    throw new Error(data.message || "Failed to update profile")
  }
}

export async function changeMyPassword(oldPassword: string, newPassword: string): Promise<void> {
  const res = await apiFetch("/api/users/me/password", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ oldPassword, newPassword }),
  })
  const data = await res.json() as ApiResponse<boolean>
  if (!data.success) {
    throw new Error(data.message || "Failed to change password")
  }
}

export async function resetMyAccessToken(): Promise<string> {
  const res = await apiFetch("/api/users/me/access-token/reset", { method: "POST" })
  const data = await res.json() as ApiResponse<string>
  if (!data.success || !data.data) {
    throw new Error(data.message || "Failed to reset access token")
  }
  return data.data
}
