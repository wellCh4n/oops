"use client"

import { useEffect, useState } from "react"
import { toast } from "sonner"
import { Server } from "lucide-react"
import { fetchEnvironments } from "@/lib/api/environments"
import { fetchNodes } from "@/lib/api/nodes"
import { Environment, NodeStatus } from "@/lib/api/types"
import { Badge } from "@/components/ui/badge"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import { useLanguage } from "@/contexts/language-context"

export default function NodesPage() {
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [selectedEnv, setSelectedEnv] = useState("")
  const [nodes, setNodes] = useState<NodeStatus[]>([])
  const [loading, setLoading] = useState(false)
  const { t } = useLanguage()

  useEffect(() => {
    void (async () => {
      try {
        const res = await fetchEnvironments()
        const envs = res.data ?? []
        setEnvironments(envs)
        if (envs.length > 0) {
          setSelectedEnv(envs[0].name)
        }
      } catch {
        toast.error(t("nodes.fetchEnvError"))
      }
    })()
  }, [t])

  useEffect(() => {
    if (!selectedEnv) return
    void (async () => {
      setLoading(true)
      try {
        const res = await fetchNodes(selectedEnv)
        setNodes(res.data ?? [])
      } catch {
        toast.error(t("nodes.fetchError"))
        setNodes([])
      } finally {
        setLoading(false)
      }
    })()
  }, [selectedEnv, t])

  return (
    <ContentPage title={t("nodes.title")}>
      <TableForm
        options={
          <div className="flex flex-col gap-1.5">
            <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Server className="w-4 h-4" />{t("common.environment")}</span>
            <SelectWithSearch
              value={selectedEnv}
              onValueChange={setSelectedEnv}
              options={environments.map((env) => ({ value: env.name, label: env.name }))}
              placeholder={t("common.selectEnvironment")}
              searchPlaceholder={t("common.search")}
              emptyText={t("common.noResults")}
              className="w-[220px]"
            />
          </div>
        }
        table={
          <div className="border rounded-md">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("nodes.col.name")}</TableHead>
                  <TableHead>{t("nodes.col.status")}</TableHead>
                  <TableHead>{t("nodes.col.role")}</TableHead>
                  <TableHead>{t("nodes.col.ip")}</TableHead>
                  <TableHead>{t("nodes.col.cpu")}</TableHead>
                  <TableHead>{t("nodes.col.memory")}</TableHead>
                  <TableHead>{t("nodes.col.pods")}</TableHead>
                  <TableHead>{t("nodes.col.version")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {loading ? (
                  <TableRow>
                    <TableCell colSpan={8} className="py-2 text-center text-muted-foreground">
                      {t("common.loading")}
                    </TableCell>
                  </TableRow>
                ) : nodes.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={8} className="h-24 text-center text-muted-foreground">
                      {t("common.noData")}
                    </TableCell>
                  </TableRow>
                ) : (
                  nodes.map((node) => (
                    <TableRow key={node.name}>
                      <TableCell className="font-medium">{node.name}</TableCell>
                      <TableCell>
                        <Badge variant={node.ready ? "default" : "destructive"}>
                          {node.ready ? t("nodes.status.ready") : t("nodes.status.notReady")}
                        </Badge>
                      </TableCell>
                      <TableCell>{node.roles || "-"}</TableCell>
                      <TableCell>{node.internalIP || "-"}</TableCell>
                      <TableCell>{node.cpu || "-"}</TableCell>
                      <TableCell>{node.memory || "-"}</TableCell>
                      <TableCell>{node.pods || "-"}</TableCell>
                      <TableCell>{node.kubeletVersion || "-"}</TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </div>
        }
      />
    </ContentPage>
  )
}
