
"use client"

import { Suspense, useState, useEffect } from "react"
import { useParams } from "next/navigation"
import { ApplicationForm } from "@/app/apps/application-form"
import {
  getApplication,
  getApplicationBuildConfig,
  getApplicationBuildEnvConfigs,
  getApplicationRuntimeSpec,
  getApplicationService,
  getApplicationExpertConfig
} from "@/lib/api/applications"
import {
  Application,
  ApplicationBuildConfig,
  ApplicationBuildEnvironmentConfig,
  ApplicationRuntimeSpec,
  ApplicationServiceConfig,
  ApplicationExpertConfig
} from "@/lib/api/types"
import { toast } from "sonner"
import { useLanguage } from "@/contexts/language-context"
import { ContentPage } from "@/components/content-page"
import { AppDetailNav } from "@/app/apps/components/app-detail-nav"
import { useWorkContextStore } from "@/store/work-context"

export default function EditAppPage() {
  const params = useParams()
  const namespace = params.namespace as string
  const name = params.name as string

  const [application, setApplication] = useState<Application | null>(null)
  const [buildConfig, setBuildConfig] = useState<ApplicationBuildConfig | undefined>(undefined)
  const [buildEnvConfigs, setBuildEnvConfigs] = useState<ApplicationBuildEnvironmentConfig[]>([])
  const [runtimeSpec, setRuntimeSpec] = useState<ApplicationRuntimeSpec | undefined>(undefined)
  const [serviceConfig, setServiceConfig] = useState<ApplicationServiceConfig | undefined>(undefined)
  const [expertConfig, setExpertConfig] = useState<ApplicationExpertConfig | undefined>(undefined)
  
  const [loading, setLoading] = useState(true)
  const { t } = useLanguage()
  const enterApp = useWorkContextStore((state) => state.enterApp)

  useEffect(() => {
    let cancelled = false
    const fetchApp = async () => {
      try {
        const [appRes, buildConfigRes, buildEnvRes, runtimeSpecRes, serviceRes, expertRes] = await Promise.all([
          getApplication(namespace, name),
          getApplicationBuildConfig(namespace, name),
          getApplicationBuildEnvConfigs(namespace, name),
          getApplicationRuntimeSpec(namespace, name),
          getApplicationService(namespace, name),
          getApplicationExpertConfig(namespace, name),
        ])

        // Another app was opened while these were in flight; its own run owns the
        // state now, and this reply would overwrite it with the previous app.
        if (cancelled) return

        if (appRes.data) {
          setApplication(appRes.data)
          enterApp({
            namespace: appRes.data.namespace,
            name: appRes.data.name,
            description: appRes.data.description,
            ownerName: appRes.data.ownerName,
            icon: appRes.data.icon,
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

        if (expertRes.data) {
            setExpertConfig(expertRes.data)
        }

      } catch (error) {
        if (cancelled) return
        console.error("Failed to fetch application:", error)
        // The page reports the failure in place — the not-found panel below covers
        // the empty state. Bouncing to /apps would take the URL away from under the
        // user and lose the address they were trying to open.
        toast.error(t("apps.detail.fetchError"))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    fetchApp()
    return () => { cancelled = true }
  }, [namespace, name, enterApp]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!loading && !application) {
    return <ContentPage title={name}>{t("apps.detail.notFound")}</ContentPage>
  }

  return (
    <ContentPage
      title={application?.name ?? name}
      actions={<AppDetailNav namespace={namespace} name={name} active="edit" />}
    >
      <Suspense fallback={null}>
        <ApplicationForm
          key={`${namespace}/${name}`}
          loading={loading}
          initialData={application ?? undefined}
          initialBuildConfig={buildConfig}
          initialBuildEnvConfigs={buildEnvConfigs}
          initialRuntimeSpec={runtimeSpec}
          initialServiceConfig={serviceConfig}
          initialExpertConfig={expertConfig}
        />
      </Suspense>
    </ContentPage>
  )
}
