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
}

export interface ApplicationBuildConfig {
  id?: string
  namespace: string
  applicationName: string
  repository?: string
  dockerFile?: string
  buildImage?: string
  environmentConfigs?: ApplicationBuildEnvironmentConfig[]
}

export interface ApplicationBuildEnvironmentConfig {
  environmentName: string
  buildCommand?: string
}

export interface ApplicationEnvironment {
  id?: string
  namespace: string
  applicationName: string
  environmentName: string
}

export interface ApplicationPerformanceConfig {
  id?: string
  namespace: string
  applicationName: string
  environmentConfigs?: ApplicationPerformanceConfigEnvironmentConfig[]
}

export interface ApplicationPerformanceConfigEnvironmentConfig {
  environmentName: string
  replicas?: number
  cpuRequest?: string
  cpuLimit?: string
  memoryRequest?: string
  memoryLimit?: string
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

export interface ConfigMap {
  key: string
  value: string
  mountPath: string
}

export interface ApplicationServiceConfig {
  id?: string
  namespace?: string
  applicationName?: string
  port?: number
  environmentConfigs?: ApplicationServiceEnvironmentConfig[]
}

export interface ApplicationServiceEnvironmentConfig {
  environmentName: string
  host?: string
}
