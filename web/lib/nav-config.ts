import { LayoutGrid, Users, Server, Layers } from "lucide-react"

export interface NavItem {
  title: string
  url: string
  icon: any
}

export interface NavGroup {
  title: string
  url?: string
  items: NavItem[]
}

export const navConfig: NavGroup[] = [
  {
    title: "应用管理",
    items: [
      {
        title: "应用",
        url: "/apps",
        icon: LayoutGrid,
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
