"use client"

import { useEffect, useRef, useState } from "react"
import { Label } from "@/components/ui/label"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog"
import { toast } from "sonner"
import { TabsContent } from "@/components/ui/tabs"
import { Copyable } from "@/components/ui/copyable"
import { Check, ExternalLink, Pencil, Plug, Globe, Plus, Trash2, X } from "lucide-react"
import { ApplicationEnvironment, ApplicationServiceConfig, ApplicationServiceEnvironmentConfig } from "@/lib/api/types"
import { updateApplicationService, checkApplicationServiceHost } from "@/lib/api/applications"
import { Domain, fetchDomains } from "@/lib/api/domains"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import { Skeleton } from "@/components/ui/skeleton"
import Link from "next/link"
import { useLanguage } from "@/contexts/language-context"

interface Props {
  initialServiceConfig?: ApplicationServiceConfig
  applicationName?: string
  namespace?: string
}

interface HostRow {
  host: string
  https: boolean
  editing: boolean
  prefix: string
  suffix: string // Domain.host, e.g. "example.com" or "*.example.com"
}

interface EnvConfigGroup {
  environmentName: string
  hosts: HostRow[]
}

function splitHost(fullHost: string, domains: Domain[]): { prefix: string; suffix: string } {
  const lower = fullHost.trim().toLowerCase()
  if (!lower) return { prefix: "", suffix: "" }
  // Prefer the longest domain host whose suffix matches — either exact match or "*.domain"-style tail
  const hosts: string[] = domains.map((d) => d.host)
  hosts.sort((a: string, b: string) => b.length - a.length)
  const matchHost = hosts.find((h: string) => lower === h || lower.endsWith("." + h))
  if (!matchHost) {
    return { prefix: lower, suffix: "" }
  }
  if (lower === matchHost) {
    return { prefix: "", suffix: matchHost }
  }
  return { prefix: lower.slice(0, -(matchHost.length + 1)), suffix: matchHost }
}

function combineHost(prefix: string, suffix: string): string {
  const p = prefix.trim().toLowerCase()
  if (!suffix) return p
  return p ? `${p}.${suffix}` : suffix
}

export function ApplicationServiceInfo({ initialServiceConfig, applicationName, namespace }: Props) {
  const [port, setPort] = useState<string>("")
  const [activeTab, setActiveTab] = useState<string | undefined>(undefined)
  const [envConfigGroups, setEnvConfigGroups] = useState<EnvConfigGroup[]>([])
  const [hostErrors, setHostErrors] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [envsLoading, setEnvsLoading] = useState(!!(namespace && applicationName))
  const [domains, setDomains] = useState<Domain[]>([])
  const [pendingApexConfirm, setPendingApexConfirm] = useState<{ envName: string; index: number } | null>(null)
  const { t } = useLanguage()
  const hasInitRef = useRef(false)

  const normalizeHost = (value: string) => value.replace(/^https?:\/\//i, "")

  const makeEmptyRow = (): HostRow => ({
    host: "",
    https: true,
    editing: true,
    prefix: "",
    suffix: "",
  })

  useEffect(() => {
    fetchDomains().then(setDomains).catch(() => setDomains([]))
  }, [])

  useEffect(() => {
    if (initialServiceConfig?.port != null) {
      setPort(String(initialServiceConfig.port))
    }
    if (initialServiceConfig?.environmentConfigs) {
      if (hasInitRef.current) return
      hasInitRef.current = true
      // Group configs by environmentName
      const grouped = new Map<string, HostRow[]>()
      initialServiceConfig.environmentConfigs.forEach((c) => {
        const hosts = grouped.get(c.environmentName) || []
        const full = c.host ? normalizeHost(c.host) : ""
        hosts.push({
          host: full,
          https: c.https ?? true,
          editing: false,
          prefix: "",
          suffix: "",
        })
        grouped.set(c.environmentName, hosts)
      })
      setEnvConfigGroups(
        Array.from(grouped.entries()).map(([environmentName, hosts]) => ({
          environmentName,
          hosts,
        }))
      )
    }
  }, [initialServiceConfig])

  const handleEnvironmentsLoaded = (envs: ApplicationEnvironment[]) => {
    setEnvConfigGroups((prev) => {
      const current = prev || []
      const next = envs.map((env) => {
        const existing = current.find((c) => c.environmentName === env.environmentName)
        return existing || { environmentName: env.environmentName, hosts: [] }
      })
      if (next.length > 0 && !activeTab) {
        setActiveTab(next[0].environmentName)
      }
      return next
    })
  }

  useEffect(() => {
    if (envConfigGroups.length > 0 && !activeTab) {
      setActiveTab(envConfigGroups[0].environmentName)
    }
  }, [envConfigGroups, activeTab])

  const addHost = (envName: string) => {
    setEnvConfigGroups((prev) =>
      prev.map((group) =>
        group.environmentName === envName
          ? { ...group, hosts: [...group.hosts, makeEmptyRow()] }
          : group
      )
    )
  }

  const updateRow = (envName: string, index: number, patch: Partial<HostRow>) => {
    setEnvConfigGroups((prev) =>
      prev.map((group) =>
        group.environmentName === envName
          ? {
              ...group,
              hosts: group.hosts.map((h, i) => (i === index ? { ...h, ...patch } : h)),
            }
          : group
      )
    )
  }

  const beginEdit = (envName: string, index: number) => {
    const group = envConfigGroups.find((g) => g.environmentName === envName)
    if (!group) return
    const row = group.hosts[index]
    const { prefix, suffix } = splitHost(row.host, domains)
    updateRow(envName, index, { editing: true, prefix, suffix })
  }

  const confirmEdit = (envName: string, index: number) => {
    const group = envConfigGroups.find((g) => g.environmentName === envName)
    if (!group) return
    const row = group.hosts[index]
    if (!row.suffix) {
      toast.error(t("apps.service.suffixRequired"))
      return
    }
    if (!row.prefix.trim()) {
      setPendingApexConfirm({ envName, index })
      return
    }
    const combined = combineHost(row.prefix, row.suffix)
    updateRow(envName, index, { host: combined, editing: false })
    void validateHost(envName, index, combined)
  }

  const confirmApexEdit = () => {
    if (!pendingApexConfirm) return
    const { envName, index } = pendingApexConfirm
    const group = envConfigGroups.find((g) => g.environmentName === envName)
    if (!group) {
      setPendingApexConfirm(null)
      return
    }
    const row = group.hosts[index]
    const combined = combineHost(row.prefix, row.suffix)
    setPendingApexConfirm(null)
    updateRow(envName, index, { host: combined, editing: false })
    void validateHost(envName, index, combined)
  }

  const cancelEdit = (envName: string, index: number) => {
    const group = envConfigGroups.find((g) => g.environmentName === envName)
    if (!group) return
    const row = group.hosts[index]
    if (!row.host) {
      // Never confirmed — remove row
      removeHost(envName, index)
      return
    }
    updateRow(envName, index, { editing: false, prefix: "", suffix: "" })
  }

  const removeHost = (envName: string, index: number) => {
    setEnvConfigGroups((prev) =>
      prev.map((group) =>
        group.environmentName === envName
          ? { ...group, hosts: group.hosts.filter((_, i) => i !== index) }
          : group
      )
    )
    setHostErrors((prev) => {
      const next: Record<string, string> = {}
      Object.entries(prev).forEach(([k, v]) => {
        const [e, iStr] = k.split("::")
        const i = Number(iStr)
        if (e !== envName) {
          next[k] = v
        } else if (i < index) {
          next[k] = v
        } else if (i > index) {
          next[`${e}::${i - 1}`] = v
        }
      })
      return next
    })
  }

  const hostErrorKey = (envName: string, index: number) => `${envName}::${index}`

  const validateHost = async (envName: string, index: number, host: string) => {
    if (!namespace || !applicationName) return
    const trimmed = host.trim()
    const key = hostErrorKey(envName, index)
    if (!trimmed) {
      setHostErrors((prev) => {
        const next = { ...prev }
        delete next[key]
        return next
      })
      return
    }
    const formatMessage = (name: string, ns: string, env: string) =>
      t("apps.service.hostDuplicated")
        .replace("{name}", name)
        .replace("{namespace}", ns)
        .replace("{environment}", env)

    // Local duplicate check first (same form)
    let localDup: { env: string } | undefined
    for (const group of envConfigGroups) {
      for (let i = 0; i < group.hosts.length; i++) {
        if (group.environmentName === envName && i === index) continue
        if (group.hosts[i].host.trim() === trimmed) {
          localDup = { env: group.environmentName }
          break
        }
      }
      if (localDup) break
    }
    if (localDup) {
      setHostErrors((prev) => ({
        ...prev,
        [key]: formatMessage(applicationName, namespace, localDup!.env),
      }))
      return
    }
    try {
      const res = await checkApplicationServiceHost(namespace, applicationName, trimmed)
      if (res.success && res.data) {
        setHostErrors((prev) => ({
          ...prev,
          [key]: formatMessage(res.data!.applicationName, res.data!.namespace, res.data!.environmentName),
        }))
      } else {
        setHostErrors((prev) => {
          const next = { ...prev }
          delete next[key]
          return next
        })
      }
    } catch {
      // ignore transient errors
    }
  }


  const onSave = async () => {
    if (!namespace || !applicationName) return
    const trimmedPort = port.trim()
    let portNum: number | undefined = undefined
    if (trimmedPort) {
      const num = Number(trimmedPort)
      if (!Number.isInteger(num) || num <= 0 || num > 65535) {
        toast.error(t("apps.service.portError"))
        return
      }
      portNum = num
    }

    // Block save if any row is still being edited
    const hasPending = envConfigGroups.some((g) => g.hosts.some((h) => h.editing))
    if (hasPending) {
      toast.error(t("apps.service.confirmPending"))
      return
    }

    // Flatten groups to environmentConfigs
    const environmentConfigs: ApplicationServiceEnvironmentConfig[] = []
    envConfigGroups.forEach((group) => {
      group.hosts.forEach((h) => {
        if (h.host.trim()) {
          environmentConfigs.push({
            environmentName: group.environmentName,
            host: h.host.trim(),
            https: h.https,
          })
        }
      })
    })

    if (Object.keys(hostErrors).length > 0) {
      const firstError = Object.values(hostErrors)[0]
      toast.error(firstError)
      return
    }

    setSaving(true)
    try {
      const res = await updateApplicationService(namespace, applicationName, {
        port: portNum,
        environmentConfigs,
      })
      if (res.success) {
        toast.success(t("apps.service.saveSuccess"))
      } else {
        toast.error(res.message || t("apps.service.saveError"))
      }
    } catch {
      toast.error(t("apps.service.saveError"))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-2 max-w-xs">
        <Label htmlFor="service-port" className="flex items-center gap-1">
          <Plug className="h-3.5 w-3.5" />
          {t("apps.service.port")}
        </Label>
        <Input
          id="service-port"
          type="number"
          inputMode="numeric"
          value={port}
          onChange={(e) => setPort(e.target.value)}
          placeholder={t("apps.service.portPlaceholder")}
          min={1}
          max={65535}
        />
      </div>
      <div className="flex flex-col gap-2">
        {envsLoading && (
          <div className="flex flex-col gap-3">
            <Skeleton className="h-9 w-64" />
            <Skeleton className="h-32 w-full" />
          </div>
        )}
        <div className={envsLoading ? "hidden" : ""}>
        <ApplicationEnvironmentSelector
          namespace={namespace}
          applicationName={applicationName}
          value={activeTab}
          onValueChange={setActiveTab}
          onEnvironmentsLoaded={handleEnvironmentsLoaded}
          onLoadingChange={setEnvsLoading}
          className="w-full"
        >
          {envConfigGroups.map((group) => (
            <TabsContent key={group.environmentName} value={group.environmentName}>
              <div className="grid gap-4 max-w-xl">
                <Label className="flex items-center gap-1">
                  <Globe className="h-3.5 w-3.5" />
                  {t("apps.service.hosts")}
                </Label>
                {domains.length === 0 ? (
                  <div className="text-sm text-muted-foreground px-3 py-2 border rounded-md border-dashed">
                    {t("apps.service.noDomainPrefix")}
                    <Link href="/networks/domains" className="inline-flex items-center gap-0.5 text-primary mx-1">
                      <span className="hover:underline">{t("apps.service.noDomainLink")}</span>
                      <ExternalLink className="h-3 w-3" />
                    </Link>
                    {t("apps.service.noDomainSuffix")}
                  </div>
                ) : (
                  group.hosts.map((hostConfig, index) => {
                    const errKey = hostErrorKey(group.environmentName, index)
                    const err = hostErrors[errKey]
                    const envName = group.environmentName
                    return (
                  <div key={index} className="flex w-full items-start gap-2">
                    {hostConfig.editing && (
                      <div className="flex h-9 items-center gap-2">
                        <Checkbox
                          id={`service-https-${envName}-${index}`}
                          checked={hostConfig.https}
                          onCheckedChange={(checked) =>
                            updateRow(envName, index, { https: checked === true })
                          }
                        />
                        <Label htmlFor={`service-https-${envName}-${index}`}>HTTPS</Label>
                      </div>
                    )}

                    <div className="flex flex-1 flex-col gap-1">
                      {hostConfig.editing ? (
                        <div className="flex items-center gap-1">
                          <Input
                            className="flex-1"
                            value={hostConfig.prefix}
                            onChange={(e) =>
                              updateRow(envName, index, { prefix: e.target.value.trim().toLowerCase() })
                            }
                            placeholder={t("apps.service.prefixPlaceholder")}
                          />
                          <span className="text-muted-foreground">.</span>
                          <Select
                            value={hostConfig.suffix}
                            onValueChange={(v) => updateRow(envName, index, { suffix: v })}
                          >
                            <SelectTrigger className="flex-1">
                              <SelectValue placeholder={t("apps.service.suffixPlaceholder")}>
                                {hostConfig.suffix}
                              </SelectValue>
                            </SelectTrigger>
                            <SelectContent>
                              {domains.length === 0 ? (
                                <div className="px-2 py-1.5 text-sm text-muted-foreground">
                                  {t("apps.service.noDomains")}
                                </div>
                              ) : (
                                domains.map((d) => (
                                  <SelectItem key={d.id} value={d.host}>
                                    <div className="flex flex-col items-start">
                                      <span>{d.host}</span>
                                      {d.description && (
                                        <span className="text-xs text-muted-foreground">{d.description}</span>
                                      )}
                                    </div>
                                  </SelectItem>
                                ))
                              )}
                            </SelectContent>
                          </Select>
                        </div>
                      ) : hostConfig.host ? (
                        <div className="flex h-9 items-center gap-2">
                          <Copyable
                            value={`${hostConfig.https ? "https" : "http"}://${hostConfig.host}`}
                            maxLength={Infinity}
                          />
                          <a
                            href={`${hostConfig.https ? "https" : "http"}://${hostConfig.host}`}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="text-muted-foreground hover:text-foreground transition-colors"
                          >
                            <ExternalLink className="h-4 w-4" />
                          </a>
                        </div>
                      ) : (
                        <span className="h-9 inline-flex items-center text-sm text-muted-foreground">
                          {t("apps.service.domainPlaceholder")}
                        </span>
                      )}
                      {err && <p className="text-xs text-destructive">{err}</p>}
                    </div>

                    {hostConfig.editing ? (
                      <Button
                        type="button"
                        variant="outline"
                        size="icon"
                        aria-label={t("apps.service.confirmHost")}
                        onClick={() => confirmEdit(envName, index)}
                      >
                        <Check className="h-4 w-4" />
                      </Button>
                    ) : (
                      <Button
                        type="button"
                        variant="outline"
                        size="icon"
                        aria-label={t("apps.service.editHost")}
                        onClick={() => beginEdit(envName, index)}
                      >
                        <Pencil className="h-4 w-4" />
                      </Button>
                    )}
                    {hostConfig.editing ? (
                      <Button
                        type="button"
                        variant="outline"
                        size="icon"
                        aria-label={t("apps.service.cancelHost")}
                        onClick={() => cancelEdit(envName, index)}
                      >
                        <X className="h-4 w-4" />
                      </Button>
                    ) : (
                      <Button
                        type="button"
                        variant="outline"
                        size="icon"
                        onClick={() => removeHost(envName, index)}
                      >
                        <Trash2 className="h-4 w-4 text-destructive" />
                      </Button>
                    )}
                  </div>
                  )
                })
              )}
                {domains.length > 0 && (
                  <Button type="button" variant="outline" className="w-full" onClick={() => addHost(group.environmentName)}>
                    <Plus className="h-4 w-4 mr-1" />
                    {t("apps.service.addHost")}
                  </Button>
                )}
              </div>
            </TabsContent>
          ))}
        </ApplicationEnvironmentSelector>
        </div>
      </div>
      <div>
        <Button onClick={onSave} disabled={saving || !namespace || !applicationName}>
          {saving ? t("common.saving") : t("common.save")}
        </Button>
      </div>
      <AlertDialog open={!!pendingApexConfirm} onOpenChange={(v) => { if (!v) setPendingApexConfirm(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("apps.service.apexConfirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("apps.service.apexConfirmDesc").replace("{host}",
                pendingApexConfirm
                  ? envConfigGroups.find((g) => g.environmentName === pendingApexConfirm.envName)?.hosts[pendingApexConfirm.index]?.suffix || ""
                  : ""
              )}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel onClick={() => setPendingApexConfirm(null)}>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction onClick={confirmApexEdit}>{t("common.confirm")}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
