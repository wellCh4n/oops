import { API_BASE_URL } from "./config";
import { ApiResponse, Pipeline } from "./types";

export const getPipelines = async (
  namespace: string,
  name: string,
  environment?: string,
  page?: number,
  size?: number
): Promise<ApiResponse<Pipeline[]>> => {
  const url = new URL(`${API_BASE_URL}/api/namespaces/${namespace}/applications/${name}/pipelines`);
  if (environment && environment !== "all") {
    url.searchParams.append("environment", environment);
  }
  if (page !== undefined) {
    url.searchParams.append("page", String(page));
  }
  if (size !== undefined) {
    url.searchParams.append("size", String(size));
  }
  const response = await fetch(url.toString());
  if (!response.ok) {
    throw new Error("Failed to fetch pipelines");
  }
  return response.json();
};

export const getPipeline = async (namespace: string, name: string, id: string): Promise<ApiResponse<Pipeline>> => {
  const response = await fetch(`${API_BASE_URL}/api/namespaces/${namespace}/applications/${name}/pipelines/${id}`);
  if (!response.ok) {
    throw new Error("Failed to fetch pipeline");
  }
  return response.json();
};

export const stopPipeline = async (namespace: string, name: string, id: string): Promise<ApiResponse<boolean>> => {
  const response = await fetch(`${API_BASE_URL}/api/namespaces/${namespace}/applications/${name}/pipelines/${id}/stop`, {
    method: "PUT",
  });
  if (!response.ok) {
    throw new Error("Failed to stop pipeline");
  }
  return response.json();
};
