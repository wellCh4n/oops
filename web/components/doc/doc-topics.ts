export interface DocTopic {
  id: string
  href: string
  title: string
}

export const DOC_TOPICS: DocTopic[] = [
  { id: "authentication", href: "/help/docs/authentication", title: "认证" },
  { id: "discovery", href: "/help/docs/discovery", title: "资源发现" },
  { id: "applications", href: "/help/docs/applications", title: "应用" },
  { id: "pipelines", href: "/help/docs/pipelines", title: "流水线" },
  { id: "deployments", href: "/help/docs/deployments", title: "部署" },
  { id: "configmaps", href: "/help/docs/configmaps", title: "ConfigMap" },
  { id: "sandbox", href: "/help/docs/sandbox", title: "沙箱" },
]
