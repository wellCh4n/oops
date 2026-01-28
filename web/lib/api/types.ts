export interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
}

export interface Environment {
  id: string
  name: string
  apiServerUrl: string
  apiServerToken: string
  workNamespace: string
  imageRepositoryUrl: string
  imageRepositoryUsername?: string
  imageRepositoryPassword?: string
  buildStorageClass: string
}

export interface Workspace {
  id: string
  name: string
}

export interface Application {
  id: string
  workspaceId: string
  name: string
  description?: string
  namespace: string
  repository: string
  dockerFile: string
  buildImage: string
}

export interface ApplicationEnvironmentConfig {
  applicationId: string,
  environmentId: string
  buildCommand?: string
  replicas?: number
}

export interface BackendApplicationEnvironmentConfig {
  namespace: string
  applicationName: string
  environmentName: string
  buildCommand?: string
  replicas?: number
}
