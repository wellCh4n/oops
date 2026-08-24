"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { Plus, Search, Server } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DataTable } from "@/components/ui/data-table"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { useLanguage } from "@/contexts/language-context"
import { isAdmin } from "@/lib/auth"
import { Domain, fetchDomains } from "@/lib/api/domains"
import { getColumns } from "./columns"
import { DomainFormDialog } from "./domain-form-dialog"

export default function DomainsPage() {
  const { t } = useLanguage()
  const [domains, setDomains] = useState<Domain[]>([])
  const [loading, setLoading] = useState(true)
  const [admin, setAdmin] = useState(false)
  const [search, setSearch] = useState("")
  const [appliedSearch, setAppliedSearch] = useState("")
  const [environmentFilter, setEnvironmentFilter] = useState("all")
  const [formOpen, setFormOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<Domain | null>(null)
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

  const environmentOptions = useMemo(() => {
    const names = new Set<string>()
    let hasUnset = false
    for (const domain of domains) {
      if (domain.environmentName) {
        names.add(domain.environmentName)
      } else {
        hasUnset = true
      }
    }
    return { names: Array.from(names).sort(), hasUnset }
  }, [domains])

  const searched = appliedSearch
    ? domains.filter((d) =>
        d.host.toLowerCase().includes(appliedSearch.toLowerCase()) ||
        (d.description ?? "").toLowerCase().includes(appliedSearch.toLowerCase())
      )
    : domains

  const filtered = environmentFilter === "all"
    ? searched
    : environmentFilter === "__unset__"
      ? searched.filter((d) => !d.environmentName)
      : searched.filter((d) => d.environmentName === environmentFilter)

  return (
    <ContentPage title={t("domains.title")}>
      <TableForm
        options={
          <div className="flex items-end justify-between gap-4">
            <div className="flex items-end gap-4">
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5">
                  <Server className="size-4" />
                  {t("domains.col.environment")}:
                </span>
                <Select value={environmentFilter} onValueChange={(value) => setEnvironmentFilter(value ?? "all")}>
                  <SelectTrigger className="w-40 cursor-pointer">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="all" className="cursor-pointer">{t("domains.envFilter.all")}</SelectItem>
                    {environmentOptions.names.map((name) => (
                      <SelectItem key={name} value={name} className="cursor-pointer">{name}</SelectItem>
                    ))}
                    {environmentOptions.hasUnset && (
                      <SelectItem value="__unset__" className="cursor-pointer">
                        {t("domains.environment.unbound")}
                      </SelectItem>
                    )}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5">
                  <Search className="size-4" />
                  {t("domains.searchLabel")}
                </span>
                <div className="flex items-center gap-2">
                  <Input
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    onKeyDown={(e) => { if (e.key === "Enter") setAppliedSearch(search) }}
                    placeholder={t("domains.searchPlaceholder")}
                    className="w-56"
                  />
                  <Button variant="outline" onClick={() => setAppliedSearch(search)}>
                    <Search className="size-4" />
                    {t("common.search")}
                  </Button>
                </div>
              </div>
            </div>
            {admin && (
              <Button onClick={() => { setEditTarget(null); setFormOpen(true) }}>
                <Plus className="size-4" />
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
        onDeleted={load}
      />
    </ContentPage>
  )
}
