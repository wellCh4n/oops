"use client"

import { Application, ApplicationBuildConfig, ApplicationBuildEnvironmentConfig, ApplicationPerformanceConfigEnvironmentConfig, ApplicationServiceConfig } from "@/lib/api/types"
import { useRouter, usePathname, useSearchParams } from "next/navigation"
import { useEffect, useRef, useState } from "react"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ApplicationBasicInfo } from "./components/application-basic-info"
import { ApplicationBuildInfo } from "./components/application-build-info"
import { ApplicationPerformanceInfo } from "./components/application-performance-info"
import { ApplicationConfigInfo } from "./components/application-config-info"
import { ApplicationServiceInfo } from "./components/application-service-info"
import { ApplicationDangerZone } from "./components/application-danger-zone"
import { Skeleton } from "@/components/ui/skeleton"
import { useLanguage } from "@/contexts/language-context"
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { ApplicationTabHandle } from "./components/application-tab-handle"

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
  const validTabs = new Set([
    "app-info",
    "build-config",
    "performance-info",
    "service-info",
    "config-info",
    "danger-zone",
  ])
  const rawTab = searchParams.get("tab")
  const currentTab = rawTab && validTabs.has(rawTab) ? rawTab : "app-info"
  const { t } = useLanguage()
  const [pendingTab, setPendingTab] = useState<string | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [savingBeforeLeave, setSavingBeforeLeave] = useState(false)
  const [applicationData, setApplicationData] = useState<Application | undefined>(initialData)
  const [buildConfigData, setBuildConfigData] = useState<ApplicationBuildConfig | undefined>(initialBuildConfig)
  const [buildEnvConfigData, setBuildEnvConfigData] = useState<ApplicationBuildEnvironmentConfig[] | undefined>(initialBuildEnvConfigs)
  const [performanceEnvConfigData, setPerformanceEnvConfigData] = useState<ApplicationPerformanceConfigEnvironmentConfig[] | undefined>(initialPerformanceEnvConfigs)
  const [serviceConfigData, setServiceConfigData] = useState<ApplicationServiceConfig | undefined>(initialServiceConfig)
  const basicInfoRef = useRef<ApplicationTabHandle>(null)
  const buildInfoRef = useRef<ApplicationTabHandle>(null)
  const performanceInfoRef = useRef<ApplicationTabHandle>(null)
  const serviceInfoRef = useRef<ApplicationTabHandle>(null)
  const configInfoRef = useRef<ApplicationTabHandle>(null)

  useEffect(() => {
    setApplicationData(initialData)
  }, [initialData])

  useEffect(() => {
    setBuildConfigData(initialBuildConfig)
  }, [initialBuildConfig])

  useEffect(() => {
    setBuildEnvConfigData(initialBuildEnvConfigs)
  }, [initialBuildEnvConfigs])

  useEffect(() => {
    setPerformanceEnvConfigData(initialPerformanceEnvConfigs)
  }, [initialPerformanceEnvConfigs])

  useEffect(() => {
    setServiceConfigData(initialServiceConfig)
  }, [initialServiceConfig])

  const navigateToTab = (value: string) => {
    const params = new URLSearchParams(searchParams.toString())
    params.set("tab", value)
    router.push(`${pathname}?${params.toString()}`)
  }

  const getCurrentTabHandle = () => {
    switch (currentTab) {
      case "app-info":
        return basicInfoRef.current
      case "build-config":
        return buildInfoRef.current
      case "performance-info":
        return performanceInfoRef.current
      case "service-info":
        return serviceInfoRef.current
      case "config-info":
        return configInfoRef.current
      default:
        return null
    }
  }

  const resetPendingNavigation = () => {
    setPendingTab(null)
    setConfirmOpen(false)
  }

  const handleTabChange = (value: string) => {
    if (value === currentTab) {
      return
    }

    const currentTabHandle = getCurrentTabHandle()
    if (currentTabHandle?.hasUnsavedChanges()) {
      setPendingTab(value)
      setConfirmOpen(true)
      return
    }

    navigateToTab(value)
  }

  const handleDiscardAndLeave = () => {
    if (!pendingTab) {
      return
    }

    const nextTab = pendingTab
    resetPendingNavigation()
    navigateToTab(nextTab)
  }

  const handleSaveAndLeave = async () => {
    const currentTabHandle = getCurrentTabHandle()
    if (!currentTabHandle || !pendingTab) {
      resetPendingNavigation()
      return
    }

    setSavingBeforeLeave(true)
    const saved = await currentTabHandle.save()
    setSavingBeforeLeave(false)

    if (!saved) {
      return
    }

    const nextTab = pendingTab
    resetPendingNavigation()
    navigateToTab(nextTab)
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
            <TabsTrigger value="danger-zone" className="px-6 cursor-pointer data-[state=active]:bg-red-600 data-[state=active]:text-white dark:data-[state=active]:bg-red-600 dark:data-[state=active]:text-white dark:data-[state=active]:border-red-600">{t("apps.tab.dangerZone")}</TabsTrigger>
          </TabsList>
        </div>

        <TabsContent value="app-info" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={4} /> : (
            <ApplicationBasicInfo
              ref={basicInfoRef}
              initialData={applicationData}
              onSaved={(nextApplication) => setApplicationData(nextApplication)}
            />
          )}
        </TabsContent>

        <TabsContent value="service-info" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationServiceInfo
              ref={serviceInfoRef}
              initialServiceConfig={serviceConfigData}
              applicationName={applicationData?.name}
              namespace={applicationData?.namespace}
              onSaved={(nextServiceConfig) => setServiceConfigData(nextServiceConfig)}
            />
          )}
        </TabsContent>

        <TabsContent value="build-config" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationBuildInfo
              ref={buildInfoRef}
              initialBuildConfig={buildConfigData}
              initialEnvConfigs={buildEnvConfigData}
              applicationId={applicationData?.id}
              applicationName={applicationData?.name}
              namespace={applicationData?.namespace}
              onSaved={(nextBuildConfig, nextBuildEnvConfigs) => {
                setBuildConfigData(nextBuildConfig)
                setBuildEnvConfigData(nextBuildEnvConfigs)
              }}
            />
          )}
        </TabsContent>

        <TabsContent value="performance-info" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationPerformanceInfo
              ref={performanceInfoRef}
              initialEnvConfigs={performanceEnvConfigData}
              applicationId={applicationData?.id}
              applicationName={applicationData?.name}
              namespace={applicationData?.namespace}
              onSaved={(nextPerformanceEnvConfigs) => setPerformanceEnvConfigData(nextPerformanceEnvConfigs)}
            />
          )}
        </TabsContent>

        <TabsContent value="config-info" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationConfigInfo
              ref={configInfoRef}
              applicationName={applicationData?.name}
              namespace={applicationData?.namespace}
            />
          )}
        </TabsContent>

        <TabsContent value="danger-zone" className="rounded-md border border-red-200 dark:border-red-900 bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationDangerZone
              namespace={applicationData?.namespace ?? ""}
              name={applicationData?.name ?? ""}
            />
          )}
        </TabsContent>
      </Tabs>

      <AlertDialog
        open={confirmOpen}
        onOpenChange={(open) => {
          if (!open && !savingBeforeLeave) {
            resetPendingNavigation()
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("apps.unsaved.title")}</AlertDialogTitle>
            <AlertDialogDescription>{t("apps.unsaved.desc")}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={savingBeforeLeave}>{t("common.cancel")}</AlertDialogCancel>
            <Button type="button" variant="outline" onClick={handleDiscardAndLeave} disabled={savingBeforeLeave}>
              {t("apps.unsaved.discard")}
            </Button>
            <Button type="button" onClick={() => { void handleSaveAndLeave() }} disabled={savingBeforeLeave}>
              {savingBeforeLeave ? t("common.saving") : t("common.save")}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
