export interface ClusterDomainInfo {
  internalDomain: string | null
  externalDomains: string[] | null
}

export interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
}

export interface Page<T> {
  total: number
  data: T[]
  size: number
  totalPages: number
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

export interface Namespace {
  id: string
  name: string
  description?: string
}

export interface NodeStatus {
  name: string
  ready: boolean
  roles: string
  internalIP: string
  kubeletVersion: string
  osImage: string
  containerRuntimeVersion: string
  cpu: string
  memory: string
  pods: string
  creationTimestamp: string
}

export interface Application {
  id: string
  workspaceId?: string
  name: string
  description?: string
  namespace: string
  owner?: string
  ownerName?: string
  sourceType?: ApplicationSourceType
}

export type DockerFileType = 'BUILTIN' | 'USER'

export interface DockerFileConfig {
  type: DockerFileType
  path?: string
  content?: string
}

export interface ApplicationBuildConfig {
  id?: string
  namespace: string
  applicationName: string
  sourceType?: ApplicationSourceType
  repository?: string
  dockerFileConfig?: DockerFileConfig
  buildImage?: string
  environmentConfigs?: ApplicationBuildEnvironmentConfig[]
}

export type ApplicationSourceType = 'GIT' | 'ZIP'

export interface GitDeployStrategyParam {
  type: 'GIT'
  branch?: string
}

export interface ZipDeployStrategyParam {
  type: 'ZIP'
  repository: string
}

export type DeployStrategyParam = GitDeployStrategyParam | ZipDeployStrategyParam

export interface DeployRequest {
  environment: string
  deployMode?: DeployMode
  strategy: DeployStrategyParam
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

export interface ApplicationRuntimeSpec {
  id?: string
  namespace: string
  applicationName: string
  environmentConfigs?: ApplicationRuntimeSpecEnvironmentConfig[]
  healthCheck?: ApplicationRuntimeSpecHealthCheck
}

export interface ApplicationRuntimeSpecEnvironmentConfig {
  environmentName: string
  replicas?: number
  cpuRequest?: string
  cpuLimit?: string
  memoryRequest?: string
  memoryLimit?: string
}

export interface ApplicationRuntimeSpecHealthCheck {
  enabled?: boolean
  path?: string
  initialDelaySeconds?: number
  periodSeconds?: number
  timeoutSeconds?: number
  failureThreshold?: number
}

export interface ApplicationPodStatus {
  name: string
  namespace: string
  status: string
  podIP: string
  nodeName: string
  containers: ApplicationContainerStatus[]
}

export interface ApplicationContainerStatus {
  name: string
  image: string
  ready: boolean
  restartCount: number
  startedAt?: string | null
}

export type PipelineStatus = 'INITIALIZED' | 'RUNNING' | 'BUILD_SUCCEEDED' | 'DEPLOYING' | 'STOPPED' | 'SUCCEEDED' | 'ERROR'

export type DeployMode = 'IMMEDIATE' | 'MANUAL'

export interface LastSuccessfulPipelineInfo {
  branch?: string | null
  deployMode: DeployMode
  publishType?: ApplicationSourceType | null
  publishRepository?: string | null
}

export interface Pipeline {
  id: string
  namespace: string
  applicationName: string
  status: PipelineStatus
  artifact: string
  environment: string
  createdTime: string
  deployMode?: DeployMode
  operatorId?: string
  operatorName?: string
}

export interface ConfigMap {
  key: string
  value: string
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
  https?: boolean
}
