import { LayoutGrid, Users, Server, Layers, GitBranch, HardDrive, SquareCode, Globe, Box, BookOpen, Image, type LucideIcon } from "lucide-react"

interface NavItem {
  title: string
  url: string
  icon: LucideIcon
  match?: (pathname: string) => boolean
  adminOnly?: boolean
}

export interface NavGroup {
  title: string
  url?: string
  items: NavItem[]
  adminOnly?: boolean
}

export const navConfig: NavGroup[] = [
  {
    title: "nav.clusters",
    adminOnly: true,
    items: [
      {
        title: "nav.nodes",
        url: "/clusters/nodes",
        icon: HardDrive,
      },
    ],
  },
  {
    title: "nav.network",
    adminOnly: true,
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
        title: "nav.pipelines",
        url: "/pipelines",
        icon: GitBranch,
        match: (pathname) => pathname === "/pipelines" || pathname.startsWith("/pipelines/") || pathname.includes("/pipelines/"),
      },
      {
        title: "nav.ide",
        url: "/ides",
        icon: SquareCode,
        match: (pathname) => pathname === "/ides" || pathname.startsWith("/ides/"),
      },
      {
        title: "nav.sandboxes",
        url: "/sandboxes",
        icon: Box,
        match: (pathname) => pathname === "/sandboxes" || pathname.startsWith("/sandboxes/"),
      },
      {
        title: "nav.assets",
        url: "/assets",
        icon: Image,
        match: (pathname) => pathname === "/assets" || pathname.startsWith("/assets/"),
      },
    ],
  },
  {
    title: "nav.systemSettings",
    url: "/settings",
    adminOnly: true,
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
  {
    title: "nav.help",
    items: [
      {
        title: "nav.docs",
        url: "/help/docs/authentication",
        icon: BookOpen,
        match: (pathname) => pathname === "/help/docs" || pathname.startsWith("/help/docs/"),
      },
    ],
  },
]

const adminOnlyPrefixes: string[] = navConfig.flatMap((group) =>
  group.items.reduce<string[]>((acc, item) => {
    if (group.adminOnly || item.adminOnly) acc.push(item.url)
    return acc
  }, [])
)

export function isAdminOnlyPath(pathname: string): boolean {
  return adminOnlyPrefixes.some(
    (prefix) => pathname === prefix || pathname.startsWith(prefix + "/")
  )
}
