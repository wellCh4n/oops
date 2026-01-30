export interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
}

export interface Environment {
  id: string
  name: string
  kubernetesApiServer: KubernetesApiServer
  workNamespace: string
  imageRepository: ImageRepository
  buildStorageClass?: string
}

export interface KubernetesApiServer {
  url: string,
  token: string
}

export interface ImageRepository {
  url: string,
  username?: string,
  password?: string
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

export interface ApplicationPodStatus {
  name: string
  namespace: string
  status: string
  image: string[]
  podIP: string
}

export type PipelineStatus = 'INITIALIZED' | 'PENDING' | 'RUNNING' | 'STOPED' | 'SUCCEEDED' | 'ERROR'

export interface Pipeline {
  id: string
  namespace: string
  applicationName: string
  status: PipelineStatus
  artifact: string
  environment: string
  createdTime: string
}
