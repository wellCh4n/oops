"use client"

import { Application, ApplicationBuildConfig, ApplicationBuildEnvironmentConfig, ApplicationPerformanceEnvironmentConfig } from "@/lib/api/types"
import { useRouter, usePathname, useSearchParams } from "next/navigation"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ApplicationBasicInfo } from "./components/application-basic-info"
import { ApplicationBuildInfo } from "./components/application-build-info"
import { ApplicationPerformanceInfo } from "./components/application-performance-info"

interface ApplicationFormProps {
  initialData?: Application
  initialBuildConfig?: ApplicationBuildConfig
  initialBuildEnvConfigs?: ApplicationBuildEnvironmentConfig[]
  initialPerformanceEnvConfigs?: ApplicationPerformanceEnvironmentConfig[]
}

export function ApplicationForm({ 
  initialData, 
  initialBuildConfig,
  initialBuildEnvConfigs,
  initialPerformanceEnvConfigs
}: ApplicationFormProps) {
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const rawTab = searchParams.get("tab")
  const currentTab = (rawTab === "source-info" || rawTab === "build-info") 
    ? "build-config" 
    : (rawTab || "app-info")

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
            <TabsTrigger value="app-info" className="px-6">应用信息</TabsTrigger>
            <TabsTrigger value="build-config" className="px-6">构建配置</TabsTrigger>
            <TabsTrigger value="performance-info" className="px-6">性能配置</TabsTrigger>
          </TabsList>
        </div>

        <TabsContent value="app-info">
          <ApplicationBasicInfo 
            initialData={initialData}
          />
        </TabsContent>

        <TabsContent value="build-config">
          <ApplicationBuildInfo 
            initialBuildConfig={initialBuildConfig}
            initialEnvConfigs={initialBuildEnvConfigs}
            applicationId={initialData?.id}
            applicationName={initialData?.name}
            namespace={initialData?.namespace}
          />
        </TabsContent>

        <TabsContent value="performance-info">
          <ApplicationPerformanceInfo 
            initialEnvConfigs={initialPerformanceEnvConfigs}
            applicationId={initialData?.id}
            applicationName={initialData?.name}
            namespace={initialData?.namespace}
          />
        </TabsContent>
      </Tabs>
    </div>
  )
}
