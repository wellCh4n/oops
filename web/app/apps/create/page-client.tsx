"use client"

import { use, useState, useEffect } from "react"
import { useRouter } from "next/navigation"
import { ApplicationForm } from "../application-form"
import { ApplicationFormValues } from "../schema"
import { createApplication } from "@/lib/api/applications"
import { fetchNamespaces } from "@/lib/api/namespaces"
import { toast } from "sonner"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Label } from "@/components/ui/label"

export default function ClientCreate({
  searchParams,
}: {
  searchParams: Promise<{ namespace?: string }>
}) {
  const router = useRouter()
  const params = use(searchParams)
  const defaultNamespace = params.namespace ?? ""

  const [namespaces, setNamespaces] = useState<string[]>([])
  const [selectedNamespace, setSelectedNamespace] = useState<string>(defaultNamespace)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    const loadNamespaces = async () => {
      try {
        const res = await fetchNamespaces()
        if (res.data && Array.isArray(res.data)) {
          setNamespaces(res.data)
          if (!defaultNamespace && res.data.length > 0) {
            setSelectedNamespace(res.data[0])
          }
        }
      } catch (error) {
        toast.error("Failed to fetch namespaces")
      }
    }
    loadNamespaces()
  }, [defaultNamespace])

  const handleSaveAppInfo = async (data: ApplicationFormValues) => {
    if (!selectedNamespace) {
      toast.error("Please select a namespace")
      return
    }

    setLoading(true)
    try {
      const { id, ...formData } = data
      const newAppPayload = {
        workspaceId: selectedNamespace,
        namespace: selectedNamespace,
        ...formData,
      }
      
      await createApplication(newAppPayload)
      toast.success("应用创建成功")
      router.push("/apps")
    } catch (error) {
      console.error("Failed to create application:", error)
      toast.error("创建应用失败")
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex-1 space-y-4">
      <ApplicationForm 
        onSaveAppInfo={handleSaveAppInfo} 
        onCancel={() => router.back()}
        submitLabel={loading ? "创建中..." : "创建"}
        showEnvConfig={false}
        namespaceSelect={
          <>
            <Label>命名空间</Label>
            <Select value={selectedNamespace} onValueChange={setSelectedNamespace}>
              <SelectTrigger>
                <SelectValue placeholder="选择命名空间" />
              </SelectTrigger>
              <SelectContent>
                {namespaces.map(ns => (
                  <SelectItem key={ns} value={ns}>{ns}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </>
        }
      />
    </div>
  )
}
