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
  gitCredential?: GitCredential
  buildStorageClass?: string
}

interface KubernetesApiServer {
  url: string,
  token: string
}

interface ImageRepository {
  url: string,
  username?: string,
  password?: string
}

interface GitCredential {
  username?: string,
  password?: string,
  privateKey?: string
}

export interface Namespace {
  id: string
  name: string
  description?: string
}

export interface NodeStatus {
  name: string
  hostname: string
  ready: boolean
  schedulable: boolean
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
  collaborators?: string[]
  collaboratorNames?: Record<string, string>
  sourceType?: ApplicationSourceType
  createdTime?: string
}

type DockerFileType = 'BUILTIN' | 'USER'

interface DockerFileConfig {
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

interface GitDeployStrategyParam {
  type: 'GIT'
  branch?: string
}

interface ZipDeployStrategyParam {
  type: 'ZIP'
  objectKey?: string
  url?: string
}

export interface GitPublishConfig {
  type: 'GIT'
  repository?: string | null
  branch?: string | null
}

export interface ZipPublishConfig {
  type: 'ZIP'
  objectKey?: string | null
  url?: string | null
}

export type PublishConfig = GitPublishConfig | ZipPublishConfig

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

export interface ApplicationExpertConfig {
  id?: string
  namespace: string
  applicationName: string
  environmentConfigs?: ApplicationExpertConfigEnvironmentConfig[]
}

export interface ApplicationExpertConfigEnvironmentConfig {
  environmentName: string
  serviceAccountName?: string
  priority?: string
  scheduledRestartEnabled?: boolean
  scheduledRestartCron?: string
  nodeNames?: string[]
}

export interface ApplicationResource {
  kind: string
  name: string
  data: string
}

export interface PodMetric {
  podName: string
  cpuMillis: number
  memoryBytes: number
}

interface ApplicationRuntimeSpecHealthCheck {
  liveness?: ApplicationRuntimeSpecProbe
  readiness?: ApplicationRuntimeSpecProbe
}

interface ApplicationRuntimeSpecProbe {
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

export interface ApplicationEvent {
  time: string
  type?: string | null
  resourceKind?: string | null
  resourceName?: string | null
  reason?: string | null
  message?: string | null
  count?: number | null
}

interface ApplicationContainerStatus {
  name: string
  image: string
  ready: boolean
  restartCount: number
  startedAt?: string | null
  reason?: string | null
}

type PipelineStatus = 'INITIALIZED' | 'RUNNING' | 'BUILD_SUCCEEDED' | 'DEPLOYING' | 'ROLLING_OUT' | 'STOPPED' | 'SUCCEEDED' | 'ERROR'

export type DeployMode = 'IMMEDIATE' | 'MANUAL'

export interface LastSuccessfulPipelineInfo {
  deployMode: DeployMode
  publishType?: ApplicationSourceType | null
  publishConfig?: PublishConfig | null
}

export type PipelineTriggerType = 'RELEASE' | 'ROLLBACK'

export interface Pipeline {
  id: string
  namespace: string
  applicationName: string
  status: PipelineStatus
  artifact: string
  environment: string
  publishType?: ApplicationSourceType | null
  publishConfig?: PublishConfig | null
  createdTime: string
  deployMode?: DeployMode
  operatorId?: string
  operatorName?: string
  message?: string | null
  triggerType?: PipelineTriggerType
  rollbackFromPipelineId?: string | null
}

export interface ConfigMap {
  key: string
  value: string
  // When true the item is stored in the application Secret; otherwise the ConfigMap.
  secret?: boolean
  // Optional absolute file path. When set, the item is mounted as a file at this path.
  mountPath?: string
  // Optional display group used to organize items in the config editor. Pure UI metadata.
  group?: string
  // Optional free-text note describing the item. Pure UI metadata.
  comment?: string
}

export interface ApplicationServiceConfig {
  id?: string
  namespace?: string
  applicationName?: string
  port?: number
  internalPorts?: number[]
  environmentConfigs?: ApplicationServiceEnvironmentConfig[]
}

export interface ApplicationServiceEnvironmentConfig {
  environmentName: string
  host?: string
  https?: boolean
}
