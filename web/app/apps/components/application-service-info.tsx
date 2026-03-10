"use client"

import { useEffect, useState } from "react"
import { Label } from "@/components/ui/label"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { ApplicationServiceConfig } from "@/lib/api/types"
import { updateApplicationService } from "@/lib/api/applications"

interface Props {
  initialServiceConfig?: ApplicationServiceConfig
  applicationName?: string
  namespace?: string
}

export function ApplicationServiceInfo({ initialServiceConfig, applicationName, namespace }: Props) {
  const [port, setPort] = useState<string>("")
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (initialServiceConfig?.port != null) {
      setPort(String(initialServiceConfig.port))
    }
  }, [initialServiceConfig])

  const onSave = async () => {
    if (!namespace || !applicationName) return
    const num = Number(port)
    if (!Number.isInteger(num) || num <= 0 || num > 65535) {
      toast.error("端口必须是 1-65535 的整数")
      return
    }
    setSaving(true)
    try {
      const res = await updateApplicationService(namespace, applicationName, { port: num })
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
      <div>
        <Button onClick={onSave} disabled={saving || !namespace || !applicationName}>
          {saving ? "保存中..." : "保存"}
        </Button>
      </div>
    </div>
  )
}
