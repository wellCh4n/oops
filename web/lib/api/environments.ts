
import { API_BASE_URL } from "./config";
import { EnvironmentFormValues } from "@/app/settings/environments/columns";
import { ApiResponse, Environment } from "./types";

export async function fetchEnvironments(): Promise<ApiResponse<Environment[]>> {
  const response = await fetch(`${API_BASE_URL}/api/environments`);
  if (!response.ok) {
    throw new Error("Failed to fetch environments");
  }
  return response.json();
}

export async function fetchEnvironment(id: string): Promise<ApiResponse<Environment>> {
  const response = await fetch(`${API_BASE_URL}/api/environments/${id}`);
  if (!response.ok) {
    throw new Error("Failed to fetch environment");
  }
  return response.json();
}

export async function createEnvironment(data: EnvironmentFormValues): Promise<ApiResponse<Environment>> {
  const response = await fetch(`${API_BASE_URL}/api/environments`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(data),
  });

  if (!response.ok) {
    throw new Error("Failed to create environment");
  }

  return response.json();
}

export interface KubernetesValidationResult {
  success: boolean;
  status: "VALID" | "CONNECTION_FAILED" | "NAMESPACE_MISSING" | "ERROR";
  message: string;
}

export async function validateKubernetes(data: { kubernetesApiServer: { url?: string; token?: string }, workNamespace: string }): Promise<ApiResponse<KubernetesValidationResult>> {
  const response = await fetch(`${API_BASE_URL}/api/kubernetes/validations`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
  if (!response.ok) throw new Error("Failed to validate kubernetes");
  return response.json();
}

export async function createNamespace(data: { kubernetesApiServer: { url?: string; token?: string }, workNamespace: string }): Promise<ApiResponse<boolean>> {
  const response = await fetch(`${API_BASE_URL}/api/kubernetes/namespaces`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
  if (!response.ok) throw new Error("Failed to create namespace");
  return response.json();
}

export async function validateImageRepository(data: { url?: string; username?: string; password?: string }): Promise<ApiResponse<boolean>> {
  const response = await fetch(`${API_BASE_URL}/api/image-repositories/validations`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
  if (!response.ok) throw new Error("Failed to validate image repository");
  return response.json();
}

export async function updateEnvironment(id: string, data: EnvironmentFormValues): Promise<ApiResponse<boolean>> {
  const response = await fetch(`${API_BASE_URL}/api/environments/${id}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(data),
  });

  if (!response.ok) {
    throw new Error("Failed to update environment");
  }

  return response.json();
}

export async function deleteEnvironment(id: string): Promise<ApiResponse<boolean>> {
  const response = await fetch(`${API_BASE_URL}/api/environments/${id}`, {
    method: "DELETE",
  });

  if (!response.ok) {
    throw new Error("Failed to delete environment");
  }

  return response.json();
}
