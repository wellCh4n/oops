import { API_BASE_URL } from "./config";
import { Application, ApiResponse, ApplicationEnvironmentConfig, ApplicationPodStatus } from "./types";

export const getApplications = async (namespace: string): Promise<ApiResponse<Application[]>> => {
  const response = await fetch(`${API_BASE_URL}/api/namespaces/${namespace}/applications`);
  if (!response.ok) {
    throw new Error("Failed to fetch applications");
  }
  return response.json();
};

export const getApplication = async (namespace: string, name: string): Promise<ApiResponse<Application>> => {
  const response = await fetch(`${API_BASE_URL}/api/namespaces/${namespace}/applications/${name}`);
  if (!response.ok) {
    throw new Error("Failed to fetch application");
  }
  return response.json();
};

export const createApplication = async (application: Partial<Application>): Promise<ApiResponse<string>> => {
  const response = await fetch(`${API_BASE_URL}/api/namespaces/${application.namespace}/applications`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(application),
  });
  if (!response.ok) {
    throw new Error("Failed to create application");
  }
  return response.json();
};

export const updateApplication = async (application: Partial<Application>): Promise<ApiResponse<boolean>> => {
  const response = await fetch(`${API_BASE_URL}/api/namespaces/${application.namespace}/applications/${application.name}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(application),
  });
  if (!response.ok) {
    throw new Error("Failed to update application");
  }
  return response.json();
};

export const deleteApplication = async (namespace: string, id: string): Promise<void> => {
  // Assuming delete API structure, though not explicitly requested yet.
  // Using ID or Name? Usually name in K8s, but let's stick to ID if that's what we have.
  // User asked for GET /api/namespaces/{namespace}/applications/{name} as search.
  // I'll leave delete for now or assume standard REST.
  // But wait, the list has ID.
  const response = await fetch(`${API_BASE_URL}/api/namespaces/${namespace}/applications/${id}`, {
    method: "DELETE",
  });
  if (!response.ok) {
    throw new Error("Failed to delete application");
  }
};

export const getApplicationConfigs = async (namespace: string, name: string): Promise<ApiResponse<ApplicationEnvironmentConfig[]>> => {
  const response = await fetch(`${API_BASE_URL}/api/namespaces/${namespace}/applications/${name}/environments/configs`);
  if (!response.ok) {
    throw new Error("Failed to fetch application environment configs");
  }
  return response.json();
};

export const upsertApplicationConfigs = async (
  namespace: string, 
  name: string, 
  configs: ApplicationEnvironmentConfig[]
): Promise<ApiResponse<boolean>> => {
  const response = await fetch(`${API_BASE_URL}/api/namespaces/${namespace}/applications/${name}/environments/configs`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(configs),
  });
  if (!response.ok) {
    throw new Error("Failed to save application environment configs");
  }
  return response.json();
};

export const deployApplication = async (
  namespace: string,
  name: string,
  environment: string
): Promise<ApiResponse<string>> => {
  const response = await fetch(`${API_BASE_URL}/api/namespaces/${namespace}/applications/${name}/deployments?environment=${environment}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
  });
  if (!response.ok) {
    throw new Error("Failed to deploy application");
  }
  return response.json();
};

export const getApplicationStatus = async (namespace: string, name: string, env: string): Promise<ApiResponse<ApplicationPodStatus[]>> => {
  const response = await fetch(`${API_BASE_URL}/api/namespaces/${namespace}/applications/${name}/status?env=${env}`);
  if (!response.ok) {
    throw new Error("Failed to fetch application status");
  }
  return response.json();
};

export const restartApplicationPod = async (namespace: string, name: string, podName: string, env: string): Promise<ApiResponse<boolean>> => {
  const response = await fetch(`${API_BASE_URL}/api/namespaces/${namespace}/applications/${name}/pods/${podName}/restart?env=${env}`, {
    method: "PUT",
  });
  if (!response.ok) {
    throw new Error("Failed to restart application pod");
  }
  return response.json();
};
