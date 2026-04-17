"use client"

import { useEffect, useMemo, useState } from "react"
import { toast } from "sonner"
import { Server } from "lucide-react"
import { fetchEnvironments } from "@/lib/api/environments"
import { fetchNodes } from "@/lib/api/nodes"
import { Environment, NodeStatus } from "@/lib/api/types"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import { DataTable } from "@/components/ui/data-table"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import { useLanguage } from "@/contexts/language-context"
import { getColumns } from "./columns"

export default function NodesPage() {
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [selectedEnv, setSelectedEnv] = useState("")
  const [nodes, setNodes] = useState<NodeStatus[]>([])
  const [loading, setLoading] = useState(false)
  const { t } = useLanguage()
  const columns = useMemo(() => getColumns(t), [t])

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
          <DataTable
            columns={columns}
            data={nodes}
            loading={loading}
            getRowId={(row) => row.name}
          />
        }
      />
    </ContentPage>
  )
}
