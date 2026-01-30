"use client"

import { useState, useEffect } from "react"
import { useRouter, useParams } from "next/navigation"
import { ApplicationForm } from "../../application-form"
import { ApplicationFormValues } from "../../schema"
import { getApplication, updateApplication, getApplicationConfigs, upsertApplicationConfigs } from "@/lib/api/applications"
import { Application } from "@/lib/api/types"
import { toast } from "sonner"
import { Skeleton } from "@/components/ui/skeleton"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { fetchEnvironments } from "@/lib/api/environments"

export default function EditAppPage() {
  const router = useRouter()
  const params = useParams()
  const namespace = params.namespace as string
  const name = params.name as string

  const [application, setApplication] = useState<Application | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [initialEnvConfigs, setInitialEnvConfigs] = useState<{ environmentId: string; buildCommand?: string; replicas?: number; cpuRequest?: string; cpuLimit?: string; memoryRequest?: string; memoryLimit?: string }[]>([])

  useEffect(() => {
    const fetchApp = async () => {
      try {
        const [appRes, envsRes, cfgRes] = await Promise.all([
          getApplication(namespace, name),
          fetchEnvironments(),
          getApplicationConfigs(namespace, name),
        ])
        if (appRes.data) {
          setApplication(appRes.data)
        }
        const envs = envsRes.data ?? []
        const cfgs = cfgRes.data ?? []
        const mapped = cfgs.map(c => {
          const envId = c.environmentId || envs.find(e => e.name === c.environmentName)?.id || c.environmentName || ""
          return {
            environmentId: envId,
            buildCommand: c.buildCommand,
            replicas: c.replicas,
            cpuRequest: c.cpuRequest,
            cpuLimit: c.cpuLimit,
            memoryRequest: c.memoryRequest,
            memoryLimit: c.memoryLimit,
          }
        })
        setInitialEnvConfigs(mapped)
      } catch (error) {
        console.error("Failed to fetch application:", error)
        toast.error("Failed to fetch application details")
        router.push("/apps")
      } finally {
        setLoading(false)
      }
    }
    fetchApp()
  }, [namespace, name, router])

  const handleSaveAppInfo = async (data: ApplicationFormValues) => {
    if (!application) return

    setSaving(true)
    try {
      const { id, ...formData } = data
      const updatePayload = {
        ...application,
        ...formData,
      }
      
      await updateApplication(updatePayload)
      toast.success("应用信息更新成功")
      // Don't redirect to allow staying on the page
    } catch (error) {
      console.error("Failed to update application:", error)
      toast.error("更新应用信息失败")
    } finally {
      setSaving(false)
    }
  }

  const handleSaveEnvConfigs = async (data: ApplicationFormValues) => {
    if (!application) return
    
    setSaving(true)
    try {
        if (data.environmentConfigs) {
          await upsertApplicationConfigs(namespace, application.name, data.environmentConfigs)
          toast.success("环境配置保存成功")
        }
    } catch (error) {
        console.error("Failed to save env configs:", error)
        toast.error("保存环境配置失败")
    } finally {
        setSaving(false)
    }
  }

  if (loading) {
    return (
      <div className="flex-1 space-y-4">
        <Skeleton className="h-10 w-48" />
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-12 w-full" />
      </div>
    )
  }

  if (!application) {
    return <div>Application not found</div>
  }

  return (
    <div className="flex-1 space-y-4">
      <ApplicationForm 
        initialData={application}
        initialEnvConfigs={initialEnvConfigs}
        onSaveAppInfo={handleSaveAppInfo}
        onSaveEnvConfigs={handleSaveEnvConfigs}
        onCancel={() => router.back()}
        submitLabel={saving ? "保存中..." : "保存修改"}
        namespaceSelect={
          <>
            <Label>命名空间</Label>
            <Input value={application.namespace} disabled />
          </>
        }
      />
    </div>
  )
}
