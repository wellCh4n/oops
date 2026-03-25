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
    title: "集群",
    items: [
      {
        title: "节点",
        url: "/nodes",
        icon: HardDrive,
      },
    ],
  },
  {
    title: "应用管理",
    items: [
      {
        title: "应用",
        url: "/apps",
        icon: LayoutGrid,
      },
      {
        title: "流水线",
        url: "/pipelines",
        icon: GitBranch,
      },
    ],
  },
  {
    title: "系统设置",
    url: "/settings",
    items: [
      {
        title: "用户",
        url: "/settings/users",
        icon: Users,
      },
      {
        title: "环境",
        url: "/settings/environments",
        icon: Server,
      },
      {
        title: "命名空间",
        url: "/settings/namespaces",
        icon: Layers,
      },
    ],
  },
]
