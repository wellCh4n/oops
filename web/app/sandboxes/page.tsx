"use client"

import { Suspense, useCallback, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { Box, Check, Plus, RefreshCw, SquareTerminal, Trash2, Server, Search, Cpu, MemoryStick, Info } from "lucide-react"
import { toast } from "sonner"
import { ColumnDef } from "@tanstack/react-table"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Label } from "@/components/ui/label"
import { Badge } from "@/components/ui/badge"
import { Switch } from "@/components/ui/switch"
import { Copyable } from "@/components/ui/copyable"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import { DataTable } from "@/components/ui/data-table"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { cn, shortImageName } from "@/lib/utils"
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog"
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent,
  AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { ChevronDown } from "lucide-react"
import { useLanguage } from "@/contexts/language-context"

import { fetchEnvironments } from "@/lib/api/environments"
import { Environment } from "@/lib/api/types"
import {
  createSandbox, deleteSandbox, listSandboxes, listSandboxImages,
  SandboxInstance, SandboxInstanceStatus,
} from "@/lib/api/sandbox"

const CUSTOM_IMAGE = "__custom__"

export default function SandboxesPage() {
  return (
    <Suspense>
      <SandboxesContent />
    </Suspense>
  )
}

function SandboxesContent() {
  const { t } = useLanguage()
  const router = useRouter()
  const searchParams = useSearchParams()

  const [environments, setEnvironments] = useState<Environment[]>([])
  const [sandboxes, setSandboxes] = useState<SandboxInstance[]>([])
  const [loading, setLoading] = useState(false)
  const [initialLoad, setInitialLoad] = useState(true)
  const envFilter = searchParams.get("env") ?? ""
  const [nameFilter, setNameFilter] = useState<string>("")
  const [nameFilterInput, setNameFilterInput] = useState<string>("")

  const updateParams = useCallback((updates: Record<string, string>) => {
    const params = new URLSearchParams(searchParams.toString())
    Object.entries(updates).forEach(([key, value]) => {
      if (value) params.set(key, value)
      else params.delete(key)
    })
    router.replace(`/sandboxes?${params.toString()}`)
  }, [router, searchParams])

  const [createOpen, setCreateOpen] = useState(false)
  const [creating, setCreating] = useState(false)
  const [createEnv, setCreateEnv] = useState("")
  const [createName, setCreateName] = useState("")
  const [images, setImages] = useState<string[]>([])
  const [selectedImage, setSelectedImage] = useState<string>("")
  const [customImage, setCustomImage] = useState("")
  const [cpuRequest, setCpuRequest] = useState("")
  const [cpuLimit, setCpuLimit] = useState("")
  const [memoryRequest, setMemoryRequest] = useState("")
  const [memoryLimit, setMemoryLimit] = useState("")
  const [envText, setEnvText] = useState("")
  const [useDefaultKeepalive, setUseDefaultKeepalive] = useState(true)

  const [deleteTarget, setDeleteTarget] = useState<SandboxInstance | null>(null)
  const [deleteConfirmText, setDeleteConfirmText] = useState("")
  const [deleting, setDeleting] = useState(false)

  useEffect(() => {
    listSandboxImages()
      .then((res) => setImages(res.data ?? []))
      .catch(() => setImages([]))
  }, [])

  useEffect(() => {
    fetchEnvironments()
      .then((res) => {
        const envs = res.data ?? []
        setEnvironments(envs)
        if (envs.length > 0 && !searchParams.get("env")) {
          updateParams({ env: envs[0].name })
        }
      })
      .catch(() => setEnvironments([]))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const fetchSandboxes = useCallback(async () => {
    if (!envFilter) return
    setLoading(true)
    try {
      const res = await listSandboxes(envFilter)
      setSandboxes(res.data ?? [])
    } catch {
      toast.error(t("sandbox.fetchError"))
    } finally {
      setLoading(false)
      setInitialLoad(false)
    }
  }, [envFilter, t])

  useEffect(() => {
    fetchSandboxes()
  }, [fetchSandboxes])

  useEffect(() => {
    const hasPending = sandboxes.some((sandbox) => sandbox.status === "PENDING" || sandbox.status === "TERMINATING")
    if (!hasPending) return
    const timer = setInterval(fetchSandboxes, 5000)
    return () => clearInterval(timer)
  }, [sandboxes, fetchSandboxes])

  const filteredSandboxes = useMemo(() => {
    const keyword = nameFilter.trim().toLowerCase()
    if (!keyword) return sandboxes
    return sandboxes.filter((sandbox) => sandbox.name.toLowerCase().includes(keyword))
  }, [sandboxes, nameFilter])

  const resetCreateForm = () => {
    setCreateEnv("")
    setCreateName("")
    setSelectedImage("")
    setCustomImage("")
    setCpuRequest("")
    setCpuLimit("")
    setMemoryRequest("")
    setMemoryLimit("")
    setEnvText("")
    setUseDefaultKeepalive(true)
  }

  const openCreateDialog = () => {
    if (!createEnv && environments.length > 0) {
      setCreateEnv(environments[0].name)
    }
    if (!selectedImage && images.length > 0) {
      setSelectedImage(images[0])
    }
    setCreateOpen(true)
  }

  const handleCreate = async () => {
    const useCustom = selectedImage === CUSTOM_IMAGE
    const image = useCustom ? customImage.trim() : selectedImage
    if (!createEnv) {
      toast.error(t("sandbox.createError"))
      return
    }
    if (!image) {
      toast.error(t("sandbox.createError"))
      return
    }
    setCreating(true)
    try {
      const cpu = (cpuRequest || cpuLimit) ? { request: cpuRequest || undefined, limit: cpuLimit || undefined } : undefined
      const memory = (memoryRequest || memoryLimit) ? { request: memoryRequest || undefined, limit: memoryLimit || undefined } : undefined
      const envNamePattern = /^[A-Za-z_][A-Za-z0-9_]*$/
      const envEntries: { name: string; value: string }[] = []
      for (const raw of envText.split("\n")) {
        const line = raw.trim()
        if (!line || line.startsWith("#")) continue
        const eq = line.indexOf("=")
        if (eq === -1) {
          toast.error(t("sandbox.envInvalidLine").replace("{line}", line))
          setCreating(false)
          return
        }
        const name = line.slice(0, eq).trim()
        const value = line.slice(eq + 1)
        if (!envNamePattern.test(name)) {
          toast.error(t("sandbox.envInvalidName").replace("{name}", name))
          setCreating(false)
          return
        }
        envEntries.push({ name, value })
      }
      const env = envEntries.length > 0
        ? Object.fromEntries(envEntries.map((entry) => [entry.name, entry.value]))
        : undefined
      const trimmedName = createName.trim()
      await createSandbox({
        environment: createEnv,
        name: trimmedName ? trimmedName : undefined,
        image,
        cpu,
        memory,
        env,
        useDefaultKeepalive,
      })
      toast.success(t("sandbox.createSuccess"))
      setCreateOpen(false)
      resetCreateForm()
      fetchSandboxes()
    } catch {
      toast.error(t("sandbox.createError"))
    } finally {
      setCreating(false)
    }
  }

  const handleDelete = async () => {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await deleteSandbox(deleteTarget.id)
      toast.success(t("sandbox.deleteSuccess"))
      setDeleteTarget(null)
      setDeleteConfirmText("")
      fetchSandboxes()
    } catch {
      toast.error(t("sandbox.deleteError"))
    } finally {
      setDeleting(false)
    }
  }

  const columns: ColumnDef<SandboxInstance>[] = [
    {
      accessorKey: "name",
      header: t("sandbox.col.name"),
      cell: ({ row }) => {
        const hasName = row.original.name && row.original.name !== row.original.id
        return (
          <div className="flex flex-col gap-0.5 items-start">
            {hasName && <span className="font-mono text-sm">{row.original.name}</span>}
            <Copyable
              value={row.original.id}
              maxLength={Infinity}
              className={hasName ? "text-muted-foreground" : undefined}
            />
          </div>
        )
      },
    },
    {
      accessorKey: "environment",
      header: t("sandbox.col.environment"),
      size: 90,
    },
    {
      accessorKey: "image",
      header: t("sandbox.col.image"),
      size: 120,
      cell: ({ row }) => {
        const fullImage = row.original.image
        return (
          <Tooltip>
            <TooltipTrigger asChild>
              <span className="font-mono text-xs truncate inline-block max-w-full">{shortImageName(fullImage)}</span>
            </TooltipTrigger>
            <TooltipContent className="max-w-160 break-all font-mono text-xs">
              {fullImage}
            </TooltipContent>
          </Tooltip>
        )
      },
    },
    {
      accessorKey: "status",
      header: t("sandbox.col.status"),
      size: 90,
      cell: ({ row }) => <StatusBadge status={row.original.status} />,
    },
    {
      accessorKey: "createdByName",
      header: t("sandbox.col.createdBy"),
      size: 100,
      cell: ({ row }) => row.original.createdByName || row.original.createdBy || <span className="text-muted-foreground">-</span>,
    },
    {
      accessorKey: "createdAt",
      header: t("sandbox.col.createdAt"),
      size: 150,
      cell: ({ row }) => {
        if (!row.original.createdAt) return "-"
        return new Date(row.original.createdAt).toLocaleString(undefined, {
          year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
        })
      },
    },
    {
      id: "actions",
      size: 180,
      cell: ({ row }) => (
        <div className="flex items-center justify-end gap-1.5">
          <Button asChild variant="outline" size="sm" className="h-8 px-2 gap-1">
            <Link href={`/sandboxes/${row.original.id}`}>
              <SquareTerminal className="size-4" />
              {t("sandbox.terminal")}
            </Link>
          </Button>
          <Button
            variant="destructive"
            size="sm"
            className="h-8 px-2 gap-1"
            onClick={() => { setDeleteTarget(row.original); setDeleteConfirmText("") }}
          >
            <Trash2 className="size-4" />
            {t("sandbox.delete")}
          </Button>
        </div>
      ),
    },
  ]

  return (
    <ContentPage title={t("sandbox.title")}>
      <TableForm
        options={
          <div className="flex items-end justify-between flex-wrap gap-4">
            <div className="flex items-center gap-4 flex-wrap">
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5">
                  <Server className="size-4" />{t("sandbox.envFilter")}
                </span>
                <SelectWithSearch
                  value={envFilter}
                  onValueChange={(value) => updateParams({ env: value })}
                  options={environments.map((env) => ({ value: env.name, label: env.name }))}
                  placeholder={t("sandbox.envPlaceholder")}
                  className="w-[200px]"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5">
                  <Search className="size-4" />{t("sandbox.nameFilter")}
                </span>
                <Input
                  value={nameFilterInput}
                  onChange={(event) => setNameFilterInput(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") {
                      setNameFilter(nameFilterInput)
                    }
                  }}
                  placeholder={t("sandbox.nameSearchPlaceholder")}
                  className="w-[200px]"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5">&nbsp;</span>
                <Button variant="outline" onClick={() => setNameFilter(nameFilterInput)} className="h-9">
                  <Search className="size-4" />
                  {t("sandbox.searchBtn")}
                </Button>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <Button variant="outline" size="sm" onClick={fetchSandboxes} disabled={loading}>
                <RefreshCw className={`size-4 ${loading ? "animate-spin" : ""}`} />
                {t("sandbox.refresh")}
              </Button>
              <Button size="sm" onClick={openCreateDialog}>
                <Plus className="size-4" />
                {t("sandbox.create")}
              </Button>
            </div>
          </div>
        }
        table={
          filteredSandboxes.length === 0 && !initialLoad ? (
            <div className="py-16 text-center text-muted-foreground text-sm border rounded-md border-dashed flex flex-col items-center gap-2">
              <Box className="size-6" />
              {t("sandbox.empty")}
            </div>
          ) : (
            <div className="overflow-x-auto">
              <DataTable columns={columns} data={filteredSandboxes} loading={initialLoad} />
            </div>
          )
        }
      />

      <Dialog open={createOpen} onOpenChange={(open) => { setCreateOpen(open); if (!open) resetCreateForm() }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("sandbox.create")}</DialogTitle>
            <DialogDescription />
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="sandbox-create-env">{t("sandbox.col.environment")}</Label>
              <SelectWithSearch
                value={createEnv}
                onValueChange={setCreateEnv}
                options={environments.map((env) => ({ value: env.name, label: env.name }))}
                placeholder={t("sandbox.envPlaceholder")}
                className="w-full"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="sandbox-create-name">{t("sandbox.col.name")} <span className="text-muted-foreground text-xs">({t("sandbox.nameOptional")})</span></Label>
              <Input
                id="sandbox-create-name"
                value={createName}
                onChange={(event) => setCreateName(event.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label>{t("sandbox.col.image")}</Label>
              <div className="grid grid-cols-2 gap-2">
                {images.map((img) => {
                  const selected = selectedImage === img
                  return (
                    <div
                      key={img}
                      role="button"
                      tabIndex={0}
                      onClick={() => setSelectedImage(img)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter" || event.key === " ") {
                          event.preventDefault()
                          setSelectedImage(img)
                        }
                      }}
                      className={cn(
                        "rounded-lg border p-3 flex items-center justify-between cursor-pointer transition-colors select-none gap-3 min-w-[10rem]",
                        selected
                          ? "border-primary bg-primary/5 text-primary"
                          : "border-border hover:bg-accent/50"
                      )}
                    >
                      <span className="text-sm font-medium font-mono">{img}</span>
                      {selected ? (
                        <div className="flex size-5 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground">
                          <Check className="size-3" />
                        </div>
                      ) : (
                        <div className="size-5 shrink-0 rounded-full border border-muted-foreground/30" />
                      )}
                    </div>
                  )
                })}
                <div
                  role="button"
                  tabIndex={0}
                  onClick={() => setSelectedImage(CUSTOM_IMAGE)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault()
                      setSelectedImage(CUSTOM_IMAGE)
                    }
                  }}
                  className={cn(
                    "rounded-lg border p-3 flex items-center justify-between cursor-pointer transition-colors select-none gap-3 min-w-[10rem]",
                    selectedImage === CUSTOM_IMAGE
                      ? "border-primary bg-primary/5 text-primary"
                      : "border-border hover:bg-accent/50"
                  )}
                >
                  <span className="text-sm font-medium">{t("sandbox.runtimeCustom")}</span>
                  {selectedImage === CUSTOM_IMAGE ? (
                    <div className="flex size-5 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground">
                      <Check className="size-3" />
                    </div>
                  ) : (
                    <div className="size-5 shrink-0 rounded-full border border-muted-foreground/30" />
                  )}
                </div>
              </div>
              {selectedImage === CUSTOM_IMAGE && (
                <Input
                  value={customImage}
                  onChange={(event) => setCustomImage(event.target.value)}
                  placeholder={t("sandbox.customImagePlaceholder")}
                  className="mt-2 font-mono text-sm"
                />
              )}
            </div>
            <Collapsible>
              <CollapsibleTrigger className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground [&[data-state=open]>svg]:rotate-180">
                <ChevronDown className="size-4 transition-transform duration-200" />
                {t("sandbox.advancedConfig")}
              </CollapsibleTrigger>
              <CollapsibleContent className="mt-3 space-y-4">
                <div className="inline-grid grid-cols-4 gap-x-4 gap-y-4">
                  <div className="space-y-1.5">
                    <Label htmlFor="sandbox-cpu-request" className="flex items-center gap-1">
                      <Cpu className="size-3.5" />{t("sandbox.cpuRequest")}
                    </Label>
                    <div className="relative w-24">
                      <Input
                        id="sandbox-cpu-request"
                        value={cpuRequest}
                        onChange={(event) => setCpuRequest(event.target.value)}
                        autoComplete="off"
                        className="pr-10"
                      />
                      <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">core</span>
                    </div>
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="sandbox-cpu-limit" className="flex items-center gap-1">
                      <Cpu className="size-3.5" />{t("sandbox.cpuLimit")}
                    </Label>
                    <div className="relative w-24">
                      <Input
                        id="sandbox-cpu-limit"
                        value={cpuLimit}
                        onChange={(event) => setCpuLimit(event.target.value)}
                        autoComplete="off"
                        className="pr-10"
                      />
                      <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">core</span>
                    </div>
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="sandbox-mem-request" className="flex items-center gap-1">
                      <MemoryStick className="size-3.5" />{t("sandbox.memoryRequest")}
                    </Label>
                    <div className="relative w-24">
                      <Input
                        id="sandbox-mem-request"
                        value={memoryRequest}
                        onChange={(event) => setMemoryRequest(event.target.value)}
                        autoComplete="off"
                        className="pr-8"
                      />
                      <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">Mi</span>
                    </div>
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="sandbox-mem-limit" className="flex items-center gap-1">
                      <MemoryStick className="size-3.5" />{t("sandbox.memoryLimit")}
                    </Label>
                    <div className="relative w-24">
                      <Input
                        id="sandbox-mem-limit"
                        value={memoryLimit}
                        onChange={(event) => setMemoryLimit(event.target.value)}
                        autoComplete="off"
                        className="pr-8"
                      />
                      <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">Mi</span>
                    </div>
                  </div>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="sandbox-env-text">{t("sandbox.envVars")}</Label>
                  <Textarea
                    id="sandbox-env-text"
                    value={envText}
                    onChange={(event) => setEnvText(event.target.value)}
                    placeholder={t("sandbox.envPlaceholderText")}
                    spellCheck={false}
                    autoComplete="off"
                    className="font-mono text-xs min-h-16 max-h-24 resize-none overflow-auto"
                  />
                </div>
                <div className="flex items-center justify-between gap-4 rounded-md border p-3">
                  <Label htmlFor="sandbox-keepalive" className="flex items-center gap-1.5 text-sm font-medium">
                    {t("sandbox.useDefaultKeepalive")}
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <button type="button" className="text-muted-foreground hover:text-foreground inline-flex items-center cursor-pointer" aria-label={t("sandbox.useDefaultKeepaliveHint")}>
                          <Info className="size-3.5" />
                        </button>
                      </TooltipTrigger>
                      <TooltipContent className="max-w-xs text-xs">
                        {t("sandbox.useDefaultKeepaliveHint")}
                      </TooltipContent>
                    </Tooltip>
                  </Label>
                  <Switch
                    id="sandbox-keepalive"
                    checked={useDefaultKeepalive}
                    onCheckedChange={setUseDefaultKeepalive}
                  />
                </div>
              </CollapsibleContent>
            </Collapsible>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCreateOpen(false)} disabled={creating}>
              {t("common.cancel")}
            </Button>
            <Button onClick={handleCreate} disabled={creating}>
              {creating ? t("sandbox.creating") : t("sandbox.create")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AlertDialog open={!!deleteTarget} onOpenChange={(open) => { if (!open && !deleting) { setDeleteTarget(null); setDeleteConfirmText("") } }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("sandbox.deleteConfirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("sandbox.deleteConfirmDesc")}
              <br />
              {t("sandbox.deleteConfirmHint")}
              <span className="font-mono text-foreground"> {deleteTarget?.name}</span>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <Input
            value={deleteConfirmText}
            onChange={(event) => setDeleteConfirmText(event.target.value)}
            placeholder={deleteTarget?.name}
            className="mt-2"
          />
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleting}>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleDelete}
              disabled={deleting || deleteConfirmText !== (deleteTarget?.name ?? "")}
              className="!bg-destructive !text-destructive-foreground hover:!bg-destructive/90"
            >
              {deleting ? t("sandbox.deleting") : t("sandbox.delete")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}

function StatusBadge({ status }: { status: SandboxInstanceStatus }) {
  const { t } = useLanguage()
  let variant: "default" | "secondary" | "destructive" | "outline" = "outline"
  if (status === "RUNNING") variant = "default"
  if (status === "PENDING" || status === "TERMINATING") variant = "secondary"
  if (status === "FAILED") variant = "destructive"
  return <Badge variant={variant}>{t(`sandbox.status.${status}`)}</Badge>
}
