"use client"

import { useEffect, useState } from "react"
import { Label } from "@/components/ui/label"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { toast } from "sonner"
import { TabsContent } from "@/components/ui/tabs"
import { Copy } from "lucide-react"
import { ApplicationEnvironment, ApplicationServiceConfig, ApplicationServiceEnvironmentConfig } from "@/lib/api/types"
import { updateApplicationService } from "@/lib/api/applications"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"

interface Props {
  initialServiceConfig?: ApplicationServiceConfig
  applicationName?: string
  namespace?: string
}

export function ApplicationServiceInfo({ initialServiceConfig, applicationName, namespace }: Props) {
  const [port, setPort] = useState<string>("")
  const [activeTab, setActiveTab] = useState<string | undefined>(undefined)
  const [environmentConfigs, setEnvironmentConfigs] = useState<ApplicationServiceEnvironmentConfig[]>([])
  const [saving, setSaving] = useState(false)

  const normalizeHost = (value: string) => value.replace(/^https?:\/\//i, "")

  useEffect(() => {
    if (initialServiceConfig?.port != null) {
      setPort(String(initialServiceConfig.port))
    }
    if (initialServiceConfig?.environmentConfigs) {
      setEnvironmentConfigs(
        initialServiceConfig.environmentConfigs.map((c) => ({
          ...c,
          host: c.host ? normalizeHost(c.host) : c.host,
          https: c.https ?? true,
        }))
      )
    }
  }, [initialServiceConfig])

  const handleEnvironmentsLoaded = (envs: ApplicationEnvironment[]) => {
    setEnvironmentConfigs((prev) => {
      const current = prev || []
      const next = envs.map((env) => {
        const existing = current.find((c) => c.environmentName === env.environmentName)
        return existing
          ? { ...existing, https: existing.https ?? true }
          : { environmentName: env.environmentName, host: "", https: true }
      })
      if (next.length > 0 && !activeTab) {
        setActiveTab(next[0].environmentName)
      }
      return next
    })
  }

  useEffect(() => {
    if (environmentConfigs.length > 0 && !activeTab) {
      setActiveTab(environmentConfigs[0].environmentName)
    }
  }, [environmentConfigs, activeTab])

  const onSave = async () => {
    if (!namespace || !applicationName) return
    const num = Number(port)
    if (!Number.isInteger(num) || num <= 0 || num > 65535) {
      toast.error("端口必须是 1-65535 的整数")
      return
    }
    setSaving(true)
    try {
      const res = await updateApplicationService(namespace, applicationName, {
        port: num,
        environmentConfigs,
      })
      if (res.success) {
        toast.success("服务端口已保存")
      } else {
        toast.error(res.message || "保存失败")
      }
    } catch (e) {
      toast.error("保存失败")
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-4">
      <div className="grid gap-2 max-w-xs">
        <Label htmlFor="service-port">端口</Label>
        <Input
          id="service-port"
          type="number"
          inputMode="numeric"
          value={port}
          onChange={(e) => setPort(e.target.value)}
          placeholder="例如 8080"
          min={1}
          max={65535}
        />
      </div>
      <div className="space-y-2">
        <ApplicationEnvironmentSelector
          namespace={namespace}
          applicationName={applicationName}
          value={activeTab}
          onValueChange={setActiveTab}
          onEnvironmentsLoaded={handleEnvironmentsLoaded}
          className="w-full"
        >
          {environmentConfigs.map((config, index) => (
            <TabsContent key={config.environmentName} value={config.environmentName}>
              <div className="grid gap-2 max-w-xl">
                <Label htmlFor={`service-host-${config.environmentName}`}>Host</Label>
                <div className="flex w-full items-center gap-2">
                  <div className="flex items-center gap-2">
                    <Checkbox
                      id={`service-https-${config.environmentName}`}
                      checked={config.https ?? true}
                      onCheckedChange={(checked) => {
                        const https = checked === true
                        setEnvironmentConfigs((prev) => {
                          const next = [...prev]
                          next[index] = { ...next[index], https }
                          return next
                        })
                      }}
                    />
                    <Label htmlFor={`service-https-${config.environmentName}`}>HTTPS</Label>
                  </div>

                  <div className="flex flex-1 items-center">
                    <div
                      className="flex h-9 items-center whitespace-nowrap rounded-md rounded-r-none border border-input bg-muted px-3 text-sm text-muted-foreground"
                    >
                      {config.https ?? true ? "https://" : "http://"}
                    </div>
                    <Input
                      id={`service-host-${config.environmentName}`}
                      className="rounded-l-none"
                      value={config.host ?? ""}
                      onChange={(e) => {
                        const host = normalizeHost(e.target.value)
                        setEnvironmentConfigs((prev) => {
                          const next = [...prev]
                          next[index] = { ...next[index], host }
                          return next
                        })
                      }}
                      placeholder="例如 app.example.com"
                    />
                  </div>

                  <Button
                    type="button"
                    variant="outline"
                    size="icon"
                    aria-label="复制完整地址"
                    disabled={!((config.host ?? "").trim())}
                    onClick={async () => {
                      const scheme = (config.https ?? true) ? "https://" : "http://"
                      const host = (config.host ?? "").trim()
                      const url = `${scheme}${host}`
                      try {
                        await navigator.clipboard.writeText(url)
                        toast.success("已复制")
                      } catch (e) {
                        toast.error("复制失败")
                      }
                    }}
                  >
                    <Copy />
                  </Button>
                </div>
              </div>
            </TabsContent>
          ))}
        </ApplicationEnvironmentSelector>
      </div>
      <div>
        <Button onClick={onSave} disabled={saving || !namespace || !applicationName}>
          {saving ? "保存中..." : "保存"}
        </Button>
      </div>
    </div>
  )
}
