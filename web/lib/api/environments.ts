
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

export async function testEnvironment(id: string): Promise<ApiResponse<boolean>> {
  const response = await fetch(`${API_BASE_URL}/api/environments/test/${id}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
  });

  if (!response.ok) {
    throw new Error("Failed to test environment connection");
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
