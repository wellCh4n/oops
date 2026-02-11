
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

export async function initializeEnvironmentStream(
  id: string,
  onProgress: (step: number, status: string, message: string) => void,
  onComplete: () => void,
  onError: (error: any) => void
) {
  try {
    const response = await fetch(`${API_BASE_URL}/api/environments/${id}/initialize`, {
      method: "POST",
    });

    if (!response.ok) {
      throw new Error("Failed to start environment initialization stream");
    }

    const reader = response.body?.getReader();
    const decoder = new TextDecoder();

    if (!reader) {
      throw new Error("Response body is null");
    }

    let buffer = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n\n");
      buffer = lines.pop() || ""; // Keep the last incomplete chunk

      for (const block of lines) {
        const linesInBlock = block.split("\n");
        let eventType = "message";
        let dataStr = "";

        for (const line of linesInBlock) {
          if (line.startsWith("event:")) {
            eventType = line.replace("event:", "").trim();
          } else if (line.startsWith("data:")) {
            dataStr = line.replace("data:", "").trim();
          }
        }

        if (eventType === "progress" && dataStr) {
          try {
            const parsed = JSON.parse(dataStr);
            onProgress(parsed.step, parsed.status, parsed.message);
          } catch (e) {
            console.error("Failed to parse progress data", e);
          }
        } else if (eventType === "complete") {
          onComplete();
          return; // Stop reading
        }
      }
    }
  } catch (e) {
    onError(e);
  }
}

export interface KubernetesValidationResult {
  success: boolean;
  status: "VALID" | "CONNECTION_FAILED" | "NAMESPACE_MISSING" | "ERROR";
  message: string;
}

export async function validateKubernetes(data: { kubernetesApiServer: { url?: string; token?: string }, workNamespace: string }): Promise<ApiResponse<KubernetesValidationResult>> {
  const response = await fetch(`${API_BASE_URL}/api/environments/validate/kubernetes`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
  if (!response.ok) throw new Error("Failed to validate kubernetes");
  return response.json();
}

export async function createNamespace(data: { kubernetesApiServer: { url?: string; token?: string }, workNamespace: string }): Promise<ApiResponse<boolean>> {
  const response = await fetch(`${API_BASE_URL}/api/environments/create-namespace`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
  if (!response.ok) throw new Error("Failed to create namespace");
  return response.json();
}

export async function validateImageRepository(data: { url?: string; username?: string; password?: string }): Promise<ApiResponse<boolean>> {
  const response = await fetch(`${API_BASE_URL}/api/environments/validate/image-repository`, {
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
