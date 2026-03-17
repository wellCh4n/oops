import { LayoutGrid, Users, Server, Layers, type LucideIcon } from "lucide-react"

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
        title: "节点概况",
        url: "/nodes",
        icon: Server,
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
        icon: Layers,
      },
    ],
  },
  {
    title: "系统设置",
    url: "/settings",
    items: [
      {
        title: "用户管理",
        url: "/settings/users",
        icon: Users,
      },
      {
        title: "环境管理",
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
