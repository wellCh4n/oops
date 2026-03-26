import { LayoutGrid, Users, Server, Layers, GitBranch, HardDrive, type LucideIcon } from "lucide-react"

export interface NavItem {
  title: string
  url: string
  icon: LucideIcon
}

export interface NavGroup {
  title: string
  url?: string
  items: NavItem[]
}

export const navConfig: NavGroup[] = [
  {
    title: "nav.cluster",
    items: [
      {
        title: "nav.nodes",
        url: "/nodes",
        icon: HardDrive,
      },
    ],
  },
  {
    title: "nav.appManagement",
    items: [
      {
        title: "nav.apps",
        url: "/apps",
        icon: LayoutGrid,
      },
      {
        title: "nav.pipelines",
        url: "/pipelines",
        icon: GitBranch,
      },
    ],
  },
  {
    title: "nav.systemSettings",
    url: "/settings",
    items: [
      {
        title: "nav.users",
        url: "/settings/users",
        icon: Users,
      },
      {
        title: "nav.environments",
        url: "/settings/environments",
        icon: Server,
      },
      {
        title: "nav.namespaces",
        url: "/settings/namespaces",
        icon: Layers,
      },
    ],
  },
]
