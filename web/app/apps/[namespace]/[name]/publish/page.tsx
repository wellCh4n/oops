"use client"

import { use, useState, useEffect } from "react"
import { useRouter } from "next/navigation"
import { Button } from "@/components/ui/button"
import { Rocket } from "lucide-react"
import { toast } from "sonner"
import { getApplication, getApplicationEnvironments, deployApplication } from "@/lib/api/applications"
import { Application, ApplicationEnvironment } from "@/lib/api/types"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { Label } from "@/components/ui/label"

interface PageProps {
  params: Promise<{
    namespace: string
    name: string
  }>
}

export default function PublishPage({ params }: PageProps) {
  const router = useRouter()
  // Unwrap params using React.use()
  const { namespace, name } = use(params)
  
  const [application, setApplication] = useState<Application | null>(null)
  const [environments, setEnvironments] = useState<ApplicationEnvironment[]>([])
  const [selectedEnv, setSelectedEnv] = useState<string>("")
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    const fetchData = async () => {
      try {
        const [appRes, envRes] = await Promise.all([
          getApplication(namespace, name),
          getApplicationEnvironments(namespace, name)
        ])
        
        if (appRes.data) {
          setApplication(appRes.data)
        }
        if (envRes.data) {
          setEnvironments(envRes.data)
          if (envRes.data.length > 0) {
            const firstEnv = envRes.data[0].environmentName
            if (firstEnv) {
              setSelectedEnv(firstEnv)
            }
          }
        }
      } catch (error) {
        toast.error("Failed to fetch application details")
      }
    }
    fetchData()
  }, [namespace, name])

  const handlePublish = async () => {
    if (!selectedEnv) {
      toast.error("请选择发布环境")
      return
    }

    setLoading(true)
    try {
      const res = await deployApplication(namespace, name, selectedEnv)
      if (res.success) {
        toast.success(`已提交发布到 ${selectedEnv}`)
        // Assuming the backend returns the pipeline ID in res.data
        if (res.data) {
             router.push(`/apps/${namespace}/${name}/pipelines/${res.data}`)
        } else {
             router.push("/apps")
        }
      } else {
        toast.error(res.message || "发布失败")
      }
    } catch (error) {
      toast.error("Failed to publish application")
    } finally {
      setLoading(false)
    }
  }

  if (!application) {
    return <div className="p-8">Loading...</div>
  }

  return (
    <div className="space-y-6 p-6">
      <div className="space-y-4">
        <div className="grid gap-2">
          <div className="text-sm font-medium">应用名称</div>
          <div className="text-sm text-muted-foreground">{application.name}</div>
        </div>
        <div className="grid gap-2">
          <div className="text-sm font-medium">命名空间</div>
          <div className="text-sm text-muted-foreground">{application.namespace}</div>
        </div>
        <div className="grid gap-2">
          <Label>发布环境</Label>
          <RadioGroup value={selectedEnv} onValueChange={setSelectedEnv} className="flex flex-row gap-6">
            {environments.map(env => (
              <div className="flex items-center space-x-2" key={env.environmentName}>
                <RadioGroupItem value={env.environmentName} id={env.environmentName} />
                <Label htmlFor={env.environmentName}>{env.environmentName}</Label>
              </div>
            ))}
          </RadioGroup>
          {environments.length === 0 && (
            <p className="text-sm text-destructive">
              该应用暂无环境配置，请先在应用详情页添加环境配置。
            </p>
          )}
        </div>
        
        <div className="pt-4">
          <Button onClick={handlePublish} disabled={loading || !selectedEnv}>
            <Rocket className="mr-2 h-4 w-4" />
            {loading ? "发布中..." : "确认发布"}
          </Button>
        </div>
      </div>
    </div>
  )
}
