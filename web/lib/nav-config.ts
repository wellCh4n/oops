import { LayoutGrid, Users, Server, Layers, GitBranch, HardDrive, SquareCode, type LucideIcon } from "lucide-react"

export interface NavItem {
  title: string
  url: string
  icon: LucideIcon
  match?: (pathname: string) => boolean
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
        url: "/namespaces",
        icon: LayoutGrid,
        match: (pathname) => pathname === "/namespaces" || (pathname.startsWith("/namespaces/") && pathname.includes("/applications") && !pathname.includes("/pipelines") && !pathname.includes("/ides")),
      },
      {
        title: "nav.ide",
        url: "/ides",
        icon: SquareCode,
        match: (pathname) => pathname.startsWith("/namespaces/") && pathname.includes("/ides"),
      },
      {
        title: "nav.pipelines",
        url: "/pipelines",
        icon: GitBranch,
        match: (pathname) => pathname.startsWith("/namespaces/") && pathname.includes("/pipelines"),
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
