"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { Plus, Search } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { DataTable } from "@/components/ui/data-table"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import { useLanguage } from "@/contexts/language-context"
import { isAdmin } from "@/lib/auth"
import { Domain, deleteDomain, fetchDomains } from "@/lib/api/domains"
import { getColumns } from "./columns"
import { DomainFormDialog } from "./domain-form-dialog"

export default function DomainsPage() {
  const { t } = useLanguage()
  const [domains, setDomains] = useState<Domain[]>([])
  const [loading, setLoading] = useState(true)
  const [admin, setAdmin] = useState(false)
  const [search, setSearch] = useState("")
  const [appliedSearch, setAppliedSearch] = useState("")
  const [formOpen, setFormOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<Domain | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Domain | null>(null)
  const columns = useMemo(() => getColumns(t), [t])

  useEffect(() => {
    setAdmin(isAdmin())
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const data = await fetchDomains()
      setDomains(data)
    } catch {
      toast.error(t("domains.fetchError"))
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => {
    void load()
  }, [load])

  const filtered = appliedSearch
    ? domains.filter((d) =>
        d.host.toLowerCase().includes(appliedSearch.toLowerCase()) ||
        (d.description ?? "").toLowerCase().includes(appliedSearch.toLowerCase())
      )
    : domains

  async function confirmDelete() {
    if (!deleteTarget) return
    try {
      await deleteDomain(deleteTarget.id)
      toast.success(t("domains.deleteSuccess"))
      load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Error")
    } finally {
      setDeleteTarget(null)
    }
  }

  return (
    <ContentPage title={t("domains.title")}>
      <TableForm
        options={
          <div className="flex items-end justify-between gap-4">
            <div className="flex flex-col gap-1.5">
              <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5">
                <Search className="w-4 h-4" />
                {t("domains.title")}
              </span>
              <div className="flex items-center space-x-2">
                <Input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") setAppliedSearch(search) }}
                  placeholder={t("domains.searchPlaceholder")}
                  className="w-56"
                />
                <Button variant="outline" onClick={() => setAppliedSearch(search)}>
                  <Search className="h-4 w-4" />
                  {t("common.search")}
                </Button>
              </div>
            </div>
            {admin && (
              <Button onClick={() => { setEditTarget(null); setFormOpen(true) }}>
                <Plus className="h-4 w-4" />
                {t("domains.createBtn")}
              </Button>
            )}
          </div>
        }
        table={
          <DataTable
            columns={columns}
            data={filtered}
            loading={loading}
            meta={{
              onEdit: (d: Domain) => { setEditTarget(d); setFormOpen(true) },
              onDelete: (d: Domain) => setDeleteTarget(d),
              isAdmin: admin,
            }}
            getRowId={(row) => row.id}
          />
        }
      />

      <DomainFormDialog
        open={formOpen}
        onOpenChange={setFormOpen}
        target={editTarget}
        onSaved={load}
      />

      <AlertDialog open={!!deleteTarget} onOpenChange={(v) => { if (!v) setDeleteTarget(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("domains.deleteTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("domains.deleteDescPrefix")}<strong>{deleteTarget?.host}</strong>{t("domains.deleteDescSuffix")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmDelete}>
              {t("domains.confirmDelete")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}
