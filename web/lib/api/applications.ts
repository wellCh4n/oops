import { apiFetch } from "./client"
import { Application, ApiResponse, ApplicationBuildConfig, ApplicationBuildEnvironmentConfig, ApplicationPerformanceConfigEnvironmentConfig, ApplicationEnvironment, ApplicationPodStatus, ConfigMap, ApplicationServiceConfig, ClusterDomainInfo, DeployMode, Page, LastSuccessfulPipelineInfo } from "./types"

export const getApplications = async (
  namespace: string,
  keyword?: string,
  page?: number,
  size?: number
): Promise<ApiResponse<Page<Application>>> => {
  const params = new URLSearchParams()
  if (keyword) params.set("keyword", keyword)
  if (page !== undefined) params.set("page", String(page))
  if (size !== undefined) params.set("size", String(size))
  const queryString = params.toString()
  const url = `/api/namespaces/${namespace}/applications${queryString ? `?${queryString}` : ""}`
  const response = await apiFetch(url)
  if (!response.ok) {
    throw new Error("Failed to fetch applications")
  }
  return response.json() as Promise<ApiResponse<Page<Application>>>
}

export const getApplicationService = async (namespace: string, name: string): Promise<ApiResponse<ApplicationServiceConfig>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/service`)
  if (!response.ok) {
    throw new Error("Failed to fetch application service config")
  }
  return response.json() as Promise<ApiResponse<ApplicationServiceConfig>>
}

export const updateApplicationService = async (
  namespace: string,
  name: string,
  config: Pick<ApplicationServiceConfig, "port" | "environmentConfigs">
): Promise<ApiResponse<boolean>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/service`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(config),
  })
  if (!response.ok) {
    throw new Error("Failed to update application service config")
  }
  return response.json() as Promise<ApiResponse<boolean>>
}

export const getApplication = async (namespace: string, name: string): Promise<ApiResponse<Application>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}`)
  if (!response.ok) {
    throw new Error("Failed to fetch application")
  }
  return response.json() as Promise<ApiResponse<Application>>
}

export const createApplication = async (application: Partial<Application>): Promise<ApiResponse<string>> => {
  const response = await apiFetch(`/api/namespaces/${application.namespace}/applications`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(application),
  })
  if (!response.ok) {
    throw new Error("Failed to create application")
  }
  return response.json() as Promise<ApiResponse<string>>
}

export const updateApplication = async (application: Partial<Application>): Promise<ApiResponse<boolean>> => {
  const response = await apiFetch(`/api/namespaces/${application.namespace}/applications/${application.name}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(application),
  })
  if (!response.ok) {
    throw new Error("Failed to update application")
  }
  return response.json() as Promise<ApiResponse<boolean>>
}

export const deleteApplication = async (namespace: string, id: string): Promise<void> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${id}`, {
    method: "DELETE",
  })
  if (!response.ok) {
    throw new Error("Failed to delete application")
  }
}

export const getApplicationBuildConfig = async (namespace: string, name: string): Promise<ApiResponse<ApplicationBuildConfig>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/build/config`)
  if (!response.ok) {
    throw new Error("Failed to fetch application build config")
  }
  return response.json() as Promise<ApiResponse<ApplicationBuildConfig>>
}

export const updateApplicationBuildConfig = async (namespace: string, name: string, config: ApplicationBuildConfig): Promise<ApiResponse<boolean>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/build/config`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(config),
  })
  if (!response.ok) {
    throw new Error("Failed to update application build config")
  }
  return response.json() as Promise<ApiResponse<boolean>>
}

export const getApplicationBuildEnvConfigs = async (namespace: string, name: string): Promise<ApiResponse<ApplicationBuildEnvironmentConfig[]>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/environments/build/configs`)
  if (!response.ok) {
    throw new Error("Failed to fetch application build environment configs")
  }
  return response.json() as Promise<ApiResponse<ApplicationBuildEnvironmentConfig[]>>
}

export const getApplicationPerformanceEnvConfigs = async (namespace: string, name: string): Promise<ApiResponse<ApplicationPerformanceConfigEnvironmentConfig[]>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/environments/performance/configs`)
  if (!response.ok) {
    throw new Error("Failed to fetch application performance environment configs")
  }
  return response.json() as Promise<ApiResponse<ApplicationPerformanceConfigEnvironmentConfig[]>>
}

export const getApplicationEnvironments = async (namespace: string, name: string): Promise<ApiResponse<ApplicationEnvironment[]>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/environments`)
  if (!response.ok) {
    throw new Error("Failed to fetch application environments")
  }
  return response.json() as Promise<ApiResponse<ApplicationEnvironment[]>>
}

export const getApplicationConfigMaps = async (namespace: string, name: string, environment: string): Promise<ApiResponse<ConfigMap[]>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/configmaps?environment=${environment}`)
  if (!response.ok) {
    throw new Error("Failed to fetch application config maps")
  }
  return response.json() as Promise<ApiResponse<ConfigMap[]>>
}

export const updateApplicationConfigMaps = async (namespace: string, name: string, environment: string, configMaps: ConfigMap[]): Promise<ApiResponse<boolean>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/configmaps?environment=${environment}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(configMaps),
  })
  if (!response.ok) {
    throw new Error("Failed to update application config maps")
  }
  return response.json() as Promise<ApiResponse<boolean>>
}

export const updateApplicationBuildEnvConfigs = async (
  namespace: string,
  name: string,
  configs: ApplicationBuildEnvironmentConfig[]
): Promise<ApiResponse<boolean>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/environments/build/configs`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(configs),
  })
  if (!response.ok) {
    throw new Error("Failed to save application build environment configs")
  }
  return response.json() as Promise<ApiResponse<boolean>>
}

export const updateApplicationPerformanceEnvConfigs = async (
  namespace: string,
  name: string,
  configs: ApplicationPerformanceConfigEnvironmentConfig[]
): Promise<ApiResponse<boolean>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/environments/performance/configs`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(configs),
  })
  if (!response.ok) {
    throw new Error("Failed to save application performance environment configs")
  }
  return response.json() as Promise<ApiResponse<boolean>>
}

export const updateApplicationEnvironments = async (
  namespace: string,
  name: string,
  configs: ApplicationEnvironment[]
): Promise<ApiResponse<boolean>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/environments`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(configs),
  })
  if (!response.ok) {
    throw new Error("Failed to save application environments")
  }
  return response.json() as Promise<ApiResponse<boolean>>
}

export const deployApplication = async (
  namespace: string,
  name: string,
  environment: string,
  branch: string = "main",
  deployMode: DeployMode = "IMMEDIATE"
): Promise<ApiResponse<string>> => {
  const params = new URLSearchParams({ environment, branch: branch || "main", deployMode })
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/deployments?${params.toString()}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
  })
  if (!response.ok) {
    throw new Error("Failed to deploy application")
  }
  return response.json() as Promise<ApiResponse<string>>
}

export const getApplicationStatus = async (namespace: string, name: string, env: string): Promise<ApiResponse<ApplicationPodStatus[]>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/status?env=${env}`)
  if (!response.ok) {
    throw new Error("Failed to fetch application status")
  }
  return response.json() as Promise<ApiResponse<ApplicationPodStatus[]>>
}

export const restartApplicationPod = async (namespace: string, name: string, podName: string, env: string): Promise<ApiResponse<boolean>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/pods/${podName}/restart?env=${env}`, {
    method: "PUT",
  })
  if (!response.ok) {
    throw new Error("Failed to restart application pod")
  }
  return response.json() as Promise<ApiResponse<boolean>>
}

export const getClusterDomain = async (namespace: string, name: string, env: string): Promise<ApiResponse<ClusterDomainInfo>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/service/cluster-domain?env=${env}`)
  if (!response.ok) {
    throw new Error("Failed to fetch cluster domain")
  }
  return response.json() as Promise<ApiResponse<ClusterDomainInfo>>
}

export const getLastSuccessfulPipeline = async (namespace: string, name: string): Promise<ApiResponse<LastSuccessfulPipelineInfo | null>> => {
  const response = await apiFetch(`/api/namespaces/${namespace}/applications/${name}/last-successful-pipeline`)
  if (!response.ok) {
    throw new Error("Failed to fetch last successful pipeline")
  }
  return response.json() as Promise<ApiResponse<LastSuccessfulPipelineInfo | null>>
}

export const searchAllApplications = async (keyword: string = ""): Promise<ApiResponse<Application[]>> => {
  const url = `/api/search/applications?keyword=${encodeURIComponent(keyword)}`
  const response = await apiFetch(url)
  if (!response.ok) {
    throw new Error("Failed to search applications")
  }
  return response.json() as Promise<ApiResponse<Application[]>>
}
