
"use client"

import { useState, useEffect } from "react"
import { useRouter, useParams } from "next/navigation"
import { ApplicationForm } from "../../application-form"
import { 
  getApplication, 
  getApplicationBuildConfig, 
  getApplicationBuildEnvConfigs, 
  getApplicationPerformanceEnvConfigs 
} from "@/lib/api/applications"
import { 
  Application, 
  ApplicationBuildConfig, 
  ApplicationBuildEnvironmentConfig, 
  ApplicationPerformanceEnvironmentConfig 
} from "@/lib/api/types"
import { toast } from "sonner"
import { Skeleton } from "@/components/ui/skeleton"

export default function EditAppPage() {
  const router = useRouter()
  const params = useParams()
  const namespace = params.namespace as string
  const name = params.name as string

  const [application, setApplication] = useState<Application | null>(null)
  const [buildConfig, setBuildConfig] = useState<ApplicationBuildConfig | undefined>(undefined)
  const [buildEnvConfigs, setBuildEnvConfigs] = useState<ApplicationBuildEnvironmentConfig[]>([])
  const [performanceEnvConfigs, setPerformanceEnvConfigs] = useState<ApplicationPerformanceEnvironmentConfig[]>([])
  
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const fetchApp = async () => {
      try {
        const [appRes, buildConfigRes, buildEnvRes, perfEnvRes] = await Promise.all([
          getApplication(namespace, name),
          getApplicationBuildConfig(namespace, name),
          getApplicationBuildEnvConfigs(namespace, name),
          getApplicationPerformanceEnvConfigs(namespace, name),
        ])

        if (appRes.data) {
          setApplication(appRes.data)
        }
        
        if (buildConfigRes.data) {
            setBuildConfig(buildConfigRes.data)
        }

        if (buildEnvRes.data) {
            setBuildEnvConfigs(buildEnvRes.data)
        }

        if (perfEnvRes.data) {
            setPerformanceEnvConfigs(perfEnvRes.data)
        }

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
        initialBuildConfig={buildConfig}
        initialBuildEnvConfigs={buildEnvConfigs}
        initialPerformanceEnvConfigs={performanceEnvConfigs}
      />
    </div>
  )
}
