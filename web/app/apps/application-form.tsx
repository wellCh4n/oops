"use client"

import { Application, ApplicationBuildConfig, ApplicationBuildEnvironmentConfig, ApplicationPerformanceConfigEnvironmentConfig, ApplicationServiceConfig } from "@/lib/api/types"
import { useRouter, usePathname, useSearchParams } from "next/navigation"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ApplicationBasicInfo } from "./components/application-basic-info"
import { ApplicationBuildInfo } from "./components/application-build-info"
import { ApplicationPerformanceInfo } from "./components/application-performance-info"
import { ApplicationConfigInfo } from "./components/application-config-info"
import { ApplicationServiceInfo } from "./components/application-service-info"
import { Skeleton } from "@/components/ui/skeleton"
import { useLanguage } from "@/contexts/language-context"

interface ApplicationFormProps {
  loading?: boolean
  initialData?: Application
  initialBuildConfig?: ApplicationBuildConfig
  initialBuildEnvConfigs?: ApplicationBuildEnvironmentConfig[]
  initialPerformanceEnvConfigs?: ApplicationPerformanceConfigEnvironmentConfig[]
  initialServiceConfig?: ApplicationServiceConfig
}

function TabContentSkeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div className="flex flex-col gap-6">
      {Array.from({ length: rows }).map((_, i) => (
        <Skeleton key={i} className="h-10 w-full" />
      ))}
    </div>
  )
}

export function ApplicationForm({
  loading,
  initialData,
  initialBuildConfig,
  initialBuildEnvConfigs,
  initialPerformanceEnvConfigs,
  initialServiceConfig
}: ApplicationFormProps) {
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const rawTab = searchParams.get("tab")
  const currentTab = (rawTab === "source-info" || rawTab === "build-info")
    ? "build-config"
    : (rawTab || "app-info")
  const { t } = useLanguage()

  const handleTabChange = (value: string) => {
    const params = new URLSearchParams(searchParams.toString())
    params.set("tab", value)
    router.push(`${pathname}?${params.toString()}`)
  }

  return (
    <div className="space-y-6 w-full">
      <Tabs value={currentTab} onValueChange={handleTabChange} className="w-full">
        <div className="flex items-center justify-between">
          <TabsList>
            <TabsTrigger value="app-info" className="px-6 cursor-pointer">{t("apps.tab.appInfo")}</TabsTrigger>
            <TabsTrigger value="build-config" className="px-6 cursor-pointer">{t("apps.tab.buildConfig")}</TabsTrigger>
            <TabsTrigger value="performance-info" className="px-6 cursor-pointer">{t("apps.tab.performanceConfig")}</TabsTrigger>
            <TabsTrigger value="service-info" className="px-6 cursor-pointer">{t("apps.tab.serviceConfig")}</TabsTrigger>
            <TabsTrigger value="config-info" className="px-6 cursor-pointer">{t("apps.tab.configMgmt")}</TabsTrigger>
          </TabsList>
        </div>

        <TabsContent value="app-info" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={4} /> : <ApplicationBasicInfo initialData={initialData} />}
        </TabsContent>

        <TabsContent value="service-info" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationServiceInfo
              initialServiceConfig={initialServiceConfig}
              applicationName={initialData?.name}
              namespace={initialData?.namespace}
            />
          )}
        </TabsContent>

        <TabsContent value="build-config" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationBuildInfo
              initialBuildConfig={initialBuildConfig}
              initialEnvConfigs={initialBuildEnvConfigs}
              applicationId={initialData?.id}
              applicationName={initialData?.name}
              namespace={initialData?.namespace}
            />
          )}
        </TabsContent>

        <TabsContent value="performance-info" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationPerformanceInfo
              initialEnvConfigs={initialPerformanceEnvConfigs}
              applicationId={initialData?.id}
              applicationName={initialData?.name}
              namespace={initialData?.namespace}
            />
          )}
        </TabsContent>

        <TabsContent value="config-info" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationConfigInfo
              applicationName={initialData?.name}
              namespace={initialData?.namespace}
            />
          )}
        </TabsContent>
      </Tabs>
    </div>
  )
}
