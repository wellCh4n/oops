
"use client"

import { useState, useEffect } from "react"
import { useRouter, useParams } from "next/navigation"
import { ApplicationForm } from "../../application-form"
import { 
  getApplication, 
  getApplicationBuildConfig, 
  getApplicationBuildEnvConfigs, 
  getApplicationRuntimeSpec,
  getApplicationService
} from "@/lib/api/applications"
import { 
  Application, 
  ApplicationBuildConfig, 
  ApplicationBuildEnvironmentConfig, 
  ApplicationRuntimeSpec,
  ApplicationServiceConfig
} from "@/lib/api/types"
import { toast } from "sonner"
import { useLanguage } from "@/contexts/language-context"
import { ContentPage } from "@/components/content-page"
import { useRecentAppStore } from "@/store/recent-app"

export default function EditAppPage() {
  const router = useRouter()
  const params = useParams()
  const namespace = params.namespace as string
  const name = params.name as string

  const [application, setApplication] = useState<Application | null>(null)
  const [buildConfig, setBuildConfig] = useState<ApplicationBuildConfig | undefined>(undefined)
  const [buildEnvConfigs, setBuildEnvConfigs] = useState<ApplicationBuildEnvironmentConfig[]>([])
  const [runtimeSpec, setRuntimeSpec] = useState<ApplicationRuntimeSpec | undefined>(undefined)
  const [serviceConfig, setServiceConfig] = useState<ApplicationServiceConfig | undefined>(undefined)
  
  const [loading, setLoading] = useState(true)
  const { t } = useLanguage()
  const { setRecentApp } = useRecentAppStore()

  useEffect(() => {
    const fetchApp = async () => {
      try {
        const [appRes, buildConfigRes, buildEnvRes, runtimeSpecRes, serviceRes] = await Promise.all([
          getApplication(namespace, name),
          getApplicationBuildConfig(namespace, name),
          getApplicationBuildEnvConfigs(namespace, name),
          getApplicationRuntimeSpec(namespace, name),
          getApplicationService(namespace, name),
        ])

        if (appRes.data) {
          setApplication(appRes.data)
          setRecentApp({
            namespace: appRes.data.namespace,
            name: appRes.data.name,
            description: appRes.data.description,
            ownerName: appRes.data.ownerName,
          })
        }
        
        if (buildConfigRes.data) {
            setBuildConfig(buildConfigRes.data)
        }

        if (buildEnvRes.data) {
            setBuildEnvConfigs(buildEnvRes.data)
        }

        if (runtimeSpecRes.data) {
            setRuntimeSpec(runtimeSpecRes.data)
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
  }, [namespace, name, router, setRecentApp]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!loading && !application) {
    return <ContentPage title={name}>{t("apps.detail.notFound")}</ContentPage>
  }

  return (
    <ContentPage title={application?.name ?? name}>
      <ApplicationForm
        key={`${namespace}/${name}`}
        loading={loading}
        initialData={application ?? undefined}
        initialBuildConfig={buildConfig}
        initialBuildEnvConfigs={buildEnvConfigs}
        initialRuntimeSpec={runtimeSpec}
        initialServiceConfig={serviceConfig}
      />
    </ContentPage>
  )
}
