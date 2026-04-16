export function applicationsPath(namespace: string) {
  return `/namespaces/${namespace}/applications`
}

export function applicationPath(namespace: string, name: string) {
  return `${applicationsPath(namespace)}/${name}`
}

export function applicationPublishPath(namespace: string, name: string) {
  return `${applicationPath(namespace, name)}/publish`
}

export function applicationStatusPath(namespace: string, name: string) {
  return `${applicationPath(namespace, name)}/status`
}

export function applicationPipelinesPath(namespace: string, name: string) {
  return `${applicationPath(namespace, name)}/pipelines`
}

export function applicationPipelinePath(namespace: string, name: string, pipelineId: string) {
  return `${applicationPipelinesPath(namespace, name)}/${pipelineId}`
}

export function applicationIdesPath(namespace: string, name: string) {
  return `${applicationPath(namespace, name)}/ides`
}

export function applicationIdePath(namespace: string, name: string, ideId: string) {
  return `${applicationIdesPath(namespace, name)}/${ideId}`
}

export function applicationPodLogsPath(namespace: string, name: string, podName: string) {
  return `${applicationPath(namespace, name)}/pods/${podName}/logs`
}

export function applicationPodTerminalPath(namespace: string, name: string, podName: string) {
  return `${applicationPath(namespace, name)}/pods/${podName}/terminal`
}
