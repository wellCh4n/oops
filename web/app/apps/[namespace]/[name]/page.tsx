
"use client"

import { useState, useEffect } from "react"
import { useRouter, useParams } from "next/navigation"
import { ApplicationForm } from "../../application-form"
import { 
  getApplication, 
  getApplicationBuildConfig, 
  getApplicationBuildEnvConfigs, 
  getApplicationPerformanceEnvConfigs,
  getApplicationService
} from "@/lib/api/applications"
import { 
  Application, 
  ApplicationBuildConfig, 
  ApplicationBuildEnvironmentConfig, 
  ApplicationPerformanceConfigEnvironmentConfig,
  ApplicationServiceConfig
} from "@/lib/api/types"
import { toast } from "sonner"
import { Skeleton } from "@/components/ui/skeleton"
import { useLanguage } from "@/contexts/language-context"
import { ContentPage } from "@/components/content-page"

export default function EditAppPage() {
  const router = useRouter()
  const params = useParams()
  const namespace = params.namespace as string
  const name = params.name as string

  const [application, setApplication] = useState<Application | null>(null)
  const [buildConfig, setBuildConfig] = useState<ApplicationBuildConfig | undefined>(undefined)
  const [buildEnvConfigs, setBuildEnvConfigs] = useState<ApplicationBuildEnvironmentConfig[]>([])
  const [performanceEnvConfigs, setPerformanceEnvConfigs] = useState<ApplicationPerformanceConfigEnvironmentConfig[]>([])
  const [serviceConfig, setServiceConfig] = useState<ApplicationServiceConfig | undefined>(undefined)
  
  const [loading, setLoading] = useState(true)
  const { t } = useLanguage()

  useEffect(() => {
    const fetchApp = async () => {
      try {
        const [appRes, buildConfigRes, buildEnvRes, perfEnvRes, serviceRes] = await Promise.all([
          getApplication(namespace, name),
          getApplicationBuildConfig(namespace, name),
          getApplicationBuildEnvConfigs(namespace, name),
          getApplicationPerformanceEnvConfigs(namespace, name),
          getApplicationService(namespace, name),
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

        if (serviceRes.data) {
            setServiceConfig(serviceRes.data)
        }

      } catch (error) {
        console.error("Failed to fetch application:", error)
        toast.error(t("apps.detail.fetchError"))
        router.push("/apps")
      } finally {
        setLoading(false)
      }
    }
    fetchApp()
  }, [namespace, name, router])

  if (loading) {
    return (
      <ContentPage title={name}>
        <Skeleton className="h-10 w-48" />
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-12 w-full" />
      </ContentPage>
    )
  }

  if (!application) {
    return <ContentPage title={name}>{t("apps.detail.notFound")}</ContentPage>
  }

  return (
    <ContentPage title={application.name}>
      <ApplicationForm 
        initialData={application}
        initialBuildConfig={buildConfig}
        initialBuildEnvConfigs={buildEnvConfigs}
        initialPerformanceEnvConfigs={performanceEnvConfigs}
        initialServiceConfig={serviceConfig}
      />
    </ContentPage>
  )
}
