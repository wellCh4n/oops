"use client"

import { Application, ApplicationBuildConfig, ApplicationBuildEnvironmentConfig, ApplicationRuntimeSpec as ApplicationRuntimeSpecType, ApplicationServiceConfig } from "@/lib/api/types"
import { useRouter, usePathname, useSearchParams } from "next/navigation"
import { RefObject, useMemo, useRef, useState } from "react"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ApplicationBasicInfo } from "./components/application-basic-info"
import { ApplicationBuildInfo } from "./components/application-build-info"
import { ApplicationRuntimeSpec } from "./components/application-runtime-spec"
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
  initialRuntimeSpec?: ApplicationRuntimeSpecType
  initialServiceConfig?: ApplicationServiceConfig
}

type ApplicationTab =
  | "app-info"
  | "build-config"
  | "runtime-spec"
  | "service-info"
  | "config-info"
  | "danger-zone"

interface ApplicationFormState {
  application?: Application
  buildConfig?: ApplicationBuildConfig
  buildEnvConfigs?: ApplicationBuildEnvironmentConfig[]
  runtimeSpec?: ApplicationRuntimeSpecType
  serviceConfig?: ApplicationServiceConfig
}

const DEFAULT_TAB: ApplicationTab = "app-info"
const VALID_TABS = new Set<ApplicationTab>([
  "app-info",
  "build-config",
  "runtime-spec",
  "service-info",
  "config-info",
  "danger-zone",
])

function TabContentSkeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div className="flex w-full flex-col gap-6">
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
  initialRuntimeSpec,
  initialServiceConfig
}: ApplicationFormProps) {
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const rawTab = searchParams.get("tab")
  const currentTab = rawTab && VALID_TABS.has(rawTab as ApplicationTab)
    ? rawTab as ApplicationTab
    : DEFAULT_TAB
  const { t } = useLanguage()
  const [pendingTab, setPendingTab] = useState<ApplicationTab | null>(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [savingBeforeLeave, setSavingBeforeLeave] = useState(false)
  const [formStateOverrides, setFormStateOverrides] = useState<Partial<ApplicationFormState>>({})
  const basicInfoRef = useRef<ApplicationTabHandle>(null)
  const buildInfoRef = useRef<ApplicationTabHandle>(null)
  const runtimeSpecRef = useRef<ApplicationTabHandle>(null)
  const serviceInfoRef = useRef<ApplicationTabHandle>(null)
  const configInfoRef = useRef<ApplicationTabHandle>(null)

  const formState = useMemo<ApplicationFormState>(() => ({
    application: initialData,
    buildConfig: initialBuildConfig,
    buildEnvConfigs: initialBuildEnvConfigs,
    runtimeSpec: initialRuntimeSpec,
    serviceConfig: initialServiceConfig,
    ...formStateOverrides,
  }), [
    formStateOverrides,
    initialBuildConfig,
    initialBuildEnvConfigs,
    initialData,
    initialRuntimeSpec,
    initialServiceConfig,
  ])

  const tabHandles = useMemo<Record<ApplicationTab, RefObject<ApplicationTabHandle | null>>>(() => ({
    "app-info": basicInfoRef,
    "build-config": buildInfoRef,
    "runtime-spec": runtimeSpecRef,
    "service-info": serviceInfoRef,
    "config-info": configInfoRef,
    "danger-zone": { current: null },
  }), [])

  const updateFormState = (patch: Partial<ApplicationFormState>) => {
    setFormStateOverrides((current) => ({
      ...current,
      ...patch,
    }))
  }

  const navigateToTab = (value: ApplicationTab) => {
    const params = new URLSearchParams(searchParams.toString())
    params.set("tab", value)
    router.push(`${pathname}?${params.toString()}`)
  }

  const resetPendingNavigation = () => {
    setPendingTab(null)
    setConfirmOpen(false)
  }

  const handleTabChange = (value: string) => {
    if (!VALID_TABS.has(value as ApplicationTab)) {
      return
    }

    const nextTab = value as ApplicationTab
    if (value === currentTab) {
      return
    }

    const currentTabHandle = tabHandles[currentTab]?.current
    if (currentTabHandle?.hasUnsavedChanges()) {
      setPendingTab(nextTab)
      setConfirmOpen(true)
      return
    }

    navigateToTab(nextTab)
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
    const currentTabHandle = tabHandles[currentTab]?.current
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
            <TabsTrigger value="runtime-spec" className="px-6 cursor-pointer">{t("apps.tab.runtimeSpec")}</TabsTrigger>
            <TabsTrigger value="service-info" className="px-6 cursor-pointer">{t("apps.tab.serviceConfig")}</TabsTrigger>
            <TabsTrigger value="config-info" className="px-6 cursor-pointer">{t("apps.tab.configMgmt")}</TabsTrigger>
            <TabsTrigger value="danger-zone" className="px-6 cursor-pointer data-[state=active]:bg-red-600 data-[state=active]:text-white dark:data-[state=active]:bg-red-600 dark:data-[state=active]:text-white dark:data-[state=active]:border-red-600">{t("apps.tab.dangerZone")}</TabsTrigger>
          </TabsList>
        </div>

        <TabsContent value="app-info" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={4} /> : (
            <ApplicationBasicInfo
              ref={basicInfoRef}
              initialData={formState.application}
              onSaved={(nextApplication) => updateFormState({ application: nextApplication })}
            />
          )}
        </TabsContent>

        <TabsContent value="service-info" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationServiceInfo
              ref={serviceInfoRef}
              initialServiceConfig={formState.serviceConfig}
              applicationName={formState.application?.name}
              namespace={formState.application?.namespace}
              onSaved={(nextServiceConfig) => updateFormState({ serviceConfig: nextServiceConfig })}
            />
          )}
        </TabsContent>

        <TabsContent value="build-config" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationBuildInfo
              ref={buildInfoRef}
              initialBuildConfig={formState.buildConfig}
              initialEnvConfigs={formState.buildEnvConfigs}
              applicationId={formState.application?.id}
              applicationName={formState.application?.name}
              namespace={formState.application?.namespace}
              onSaved={(nextBuildConfig, nextBuildEnvConfigs) => {
                updateFormState({
                  buildConfig: nextBuildConfig,
                  buildEnvConfigs: nextBuildEnvConfigs,
                })
              }}
            />
          )}
        </TabsContent>

        <TabsContent value="runtime-spec" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationRuntimeSpec
              ref={runtimeSpecRef}
              initialRuntimeSpec={formState.runtimeSpec}
              applicationId={formState.application?.id}
              applicationName={formState.application?.name}
              namespace={formState.application?.namespace}
              onSaved={(nextRuntimeSpec) => updateFormState({ runtimeSpec: nextRuntimeSpec })}
            />
          )}
        </TabsContent>

        <TabsContent value="config-info" className="rounded-md border bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationConfigInfo
              ref={configInfoRef}
              applicationName={formState.application?.name}
              namespace={formState.application?.namespace}
            />
          )}
        </TabsContent>

        <TabsContent value="danger-zone" className="rounded-md border border-red-200 dark:border-red-900 bg-background p-4">
          {loading ? <TabContentSkeleton rows={3} /> : (
            <ApplicationDangerZone
              namespace={formState.application?.namespace ?? ""}
              name={formState.application?.name ?? ""}
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
