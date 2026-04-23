import { LayoutGrid, Users, Server, Layers, GitBranch, HardDrive, SquareCode, Globe, type LucideIcon } from "lucide-react"

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
    title: "nav.network",
    items: [
      {
        title: "nav.domains",
        url: "/networks/domains",
        icon: Globe,
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
        match: (pathname) => pathname === "/apps" || (pathname.startsWith("/apps/") && !pathname.includes("/pipelines/")),
      },
      {
        title: "nav.ide",
        url: "/ides",
        icon: SquareCode,
        match: (pathname) => pathname === "/ides" || pathname.startsWith("/ides/"),
      },
      {
        title: "nav.pipelines",
        url: "/pipelines",
        icon: GitBranch,
        match: (pathname) => pathname === "/pipelines" || pathname.startsWith("/pipelines/") || pathname.includes("/pipelines/"),
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
