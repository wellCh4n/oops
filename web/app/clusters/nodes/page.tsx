"use client"

import { useEffect, useMemo, useState } from "react"
import { toast } from "sonner"
import { Server } from "lucide-react"
import { fetchEnvironments } from "@/lib/api/environments"
import { fetchNodes, setNodeSchedulable } from "@/lib/api/nodes"
import { Environment, NodeStatus } from "@/lib/api/types"
import { isAdmin } from "@/lib/auth"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import { DataTable } from "@/components/ui/data-table"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { useLanguage } from "@/contexts/language-context"
import { getColumns } from "./columns"

export default function NodesPage() {
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [selectedEnv, setSelectedEnv] = useState("")
  const [nodes, setNodes] = useState<NodeStatus[]>([])
  const [loading, setLoading] = useState(false)
  const [pendingNode, setPendingNode] = useState<NodeStatus | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [canManage, setCanManage] = useState(false)
  const { t } = useLanguage()

  useEffect(() => {
    setCanManage(isAdmin())
  }, [])

  const loadNodes = useMemo(
    () => async (env: string) => {
      if (!env) return
      setLoading(true)
      try {
        const res = await fetchNodes(env)
        setNodes(res.data ?? [])
      } catch {
        toast.error(t("nodes.fetchError"))
        setNodes([])
      } finally {
        setLoading(false)
      }
    },
    [t]
  )

  const columns = useMemo(
    () => getColumns(t, { canManage, onToggleSchedulable: (node) => setPendingNode(node) }),
    [t, canManage]
  )

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
    void loadNodes(selectedEnv)
  }, [selectedEnv, loadNodes])

  const confirmToggle = async () => {
    if (!pendingNode) return
    const nextSchedulable = !pendingNode.schedulable
    setSubmitting(true)
    try {
      await setNodeSchedulable(selectedEnv, pendingNode.name, nextSchedulable)
      toast.success(nextSchedulable ? t("nodes.uncordonSuccess") : t("nodes.cordonSuccess"))
      setPendingNode(null)
      await loadNodes(selectedEnv)
    } catch {
      toast.error(t("nodes.updateError"))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <ContentPage title={t("nodes.title")}>
      <TableForm
        options={
          <div className="flex flex-col gap-1.5">
            <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Server className="size-4" />{t("common.environment")}</span>
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

      <AlertDialog open={!!pendingNode} onOpenChange={(open) => { if (!open) setPendingNode(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {pendingNode?.schedulable ? t("nodes.cordonConfirmTitle") : t("nodes.uncordonConfirmTitle")}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {(pendingNode?.schedulable ? t("nodes.cordonConfirmDesc") : t("nodes.uncordonConfirmDesc"))
                .replace("{name}", pendingNode?.name ?? "")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={submitting} className="cursor-pointer">{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction disabled={submitting} onClick={(e) => { e.preventDefault(); void confirmToggle() }} className="cursor-pointer">
              {t("common.confirm")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}
