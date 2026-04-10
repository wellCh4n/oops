"use client"

import { useEffect, useState } from "react"
import { Label } from "@/components/ui/label"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { toast } from "sonner"
import { TabsContent } from "@/components/ui/tabs"
import { Copy, Plug, Globe, Plus, Trash2 } from "lucide-react"
import { ApplicationEnvironment, ApplicationServiceConfig, ApplicationServiceEnvironmentConfig } from "@/lib/api/types"
import { updateApplicationService } from "@/lib/api/applications"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import { Skeleton } from "@/components/ui/skeleton"
import { useLanguage } from "@/contexts/language-context"

interface Props {
  initialServiceConfig?: ApplicationServiceConfig
  applicationName?: string
  namespace?: string
}

interface EnvConfigGroup {
  environmentName: string
  hosts: { host: string; https: boolean }[]
}

export function ApplicationServiceInfo({ initialServiceConfig, applicationName, namespace }: Props) {
  const [port, setPort] = useState<string>("")
  const [activeTab, setActiveTab] = useState<string | undefined>(undefined)
  const [envConfigGroups, setEnvConfigGroups] = useState<EnvConfigGroup[]>([])
  const [saving, setSaving] = useState(false)
  const [envsLoading, setEnvsLoading] = useState(!!(namespace && applicationName))
  const { t } = useLanguage()

  const normalizeHost = (value: string) => value.replace(/^https?:\/\//i, "")

  useEffect(() => {
    if (initialServiceConfig?.port != null) {
      setPort(String(initialServiceConfig.port))
    }
    if (initialServiceConfig?.environmentConfigs) {
      // Group configs by environmentName
      const grouped = new Map<string, { host: string; https: boolean }[]>()
      initialServiceConfig.environmentConfigs.forEach((c) => {
        const hosts = grouped.get(c.environmentName) || []
        hosts.push({
          host: c.host ? normalizeHost(c.host) : "",
          https: c.https ?? true,
        })
        grouped.set(c.environmentName, hosts)
      })
      setEnvConfigGroups(
        Array.from(grouped.entries()).map(([environmentName, hosts]) => ({
          environmentName,
          hosts: hosts.length > 0 ? hosts : [{ host: "", https: true }],
        }))
      )
    }
  }, [initialServiceConfig])

  const handleEnvironmentsLoaded = (envs: ApplicationEnvironment[]) => {
    setEnvConfigGroups((prev) => {
      const current = prev || []
      const next = envs.map((env) => {
        const existing = current.find((c) => c.environmentName === env.environmentName)
        return existing || { environmentName: env.environmentName, hosts: [{ host: "", https: true }] }
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
          ? { ...group, hosts: [...group.hosts, { host: "", https: true }] }
          : group
      )
    )
  }

  const removeHost = (envName: string, index: number) => {
    setEnvConfigGroups((prev) =>
      prev.map((group) =>
        group.environmentName === envName
          ? { ...group, hosts: group.hosts.filter((_, i) => i !== index) }
          : group
      )
    )
  }

  const updateHost = (envName: string, index: number, field: "host" | "https", value: string | boolean) => {
    setEnvConfigGroups((prev) =>
      prev.map((group) =>
        group.environmentName === envName
          ? {
              ...group,
              hosts: group.hosts.map((h, i) =>
                i === index ? { ...h, [field]: field === "host" ? normalizeHost(value as string) : value } : h
              ),
            }
          : group
      )
    )
  }

  const onSave = async () => {
    if (!namespace || !applicationName) return
    const num = Number(port)
    if (!Number.isInteger(num) || num <= 0 || num > 65535) {
      toast.error(t("apps.service.portError"))
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

    setSaving(true)
    try {
      const res = await updateApplicationService(namespace, applicationName, {
        port: num,
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
                {group.hosts.map((hostConfig, index) => (
                  <div key={index} className="flex w-full items-center gap-2">
                    <div className="flex items-center gap-2">
                      <Checkbox
                        id={`service-https-${group.environmentName}-${index}`}
                        checked={hostConfig.https}
                        onCheckedChange={(checked) =>
                          updateHost(group.environmentName, index, "https", checked === true)
                        }
                      />
                      <Label htmlFor={`service-https-${group.environmentName}-${index}`}>HTTPS</Label>
                    </div>

                    <div className="relative flex-1">
                      <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">
                        {hostConfig.https ? "https://" : "http://"}
                      </span>
                      <Input
                        id={`service-host-${group.environmentName}-${index}`}
                        className="pl-[4.5rem]"
                        value={hostConfig.host}
                        onChange={(e) => updateHost(group.environmentName, index, "host", e.target.value)}
                        placeholder={t("apps.service.domainPlaceholder")}
                      />
                    </div>

                    <Button
                      type="button"
                      variant="outline"
                      size="icon"
                      aria-label={t("apps.service.copyAddress")}
                      disabled={!hostConfig.host.trim()}
                      onClick={async () => {
                        const scheme = hostConfig.https ? "https://" : "http://"
                        const url = `${scheme}${hostConfig.host.trim()}`
                        try {
                          await navigator.clipboard.writeText(url)
                          toast.success(t("apps.service.copied"))
                        } catch {
                          toast.error(t("apps.service.copyError"))
                        }
                      }}
                    >
                      <Copy />
                    </Button>

                    <Button
                      type="button"
                      variant="outline"
                      size="icon"
                      onClick={() => removeHost(group.environmentName, index)}
                    >
                      <Trash2 className="h-4 w-4 text-destructive" />
                    </Button>
                  </div>
                ))}
                <Button type="button" variant="outline" className="w-full" onClick={() => addHost(group.environmentName)}>
                  <Plus className="h-4 w-4 mr-1" />
                  {t("apps.service.addHost")}
                </Button>
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
    </div>
  )
}
