export interface DocTopic {
  id: string
  href: string
  titleKey: string
}

export const DOC_TOPICS: DocTopic[] = [
  { id: "authentication", href: "/help/docs/authentication", titleKey: "doc.authentication.title" },
  { id: "discovery", href: "/help/docs/discovery", titleKey: "doc.discovery.title" },
  { id: "applications", href: "/help/docs/applications", titleKey: "doc.applications.title" },
  { id: "pipelines", href: "/help/docs/pipelines", titleKey: "doc.pipelines.title" },
  { id: "deployments", href: "/help/docs/deployments", titleKey: "doc.deployments.title" },
  { id: "configmaps", href: "/help/docs/configmaps", titleKey: "doc.configmaps.title" },
  { id: "sandbox", href: "/help/docs/sandbox", titleKey: "doc.sandbox.title" },
]
