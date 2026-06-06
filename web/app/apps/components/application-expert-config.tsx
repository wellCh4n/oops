"use client"

import { forwardRef, useCallback, useEffect, useState } from "react"
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form"
import { Button } from "@/components/ui/button"
import { useForm, useFieldArray, useFormContext } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import dynamic from "next/dynamic"
const Editor = dynamic(() => import("@monaco-editor/react"), { ssr: false })
import { ApplicationExpertConfigFormValues, applicationExpertConfigSchema } from "../schema"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ApplicationExpertConfig as ApplicationExpertConfigType, ApplicationEnvironment, ApplicationResource } from "@/lib/api/types"
import { getApplicationResources, updateApplicationExpertConfig } from "@/lib/api/applications"
import { fetchServiceAccounts } from "@/lib/api/service-accounts"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import { FileCode, KeyRound, RefreshCw, Wrench } from "lucide-react"
import { toast } from "sonner"
import { useTheme } from "next-themes"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import { useLanguage } from "@/contexts/language-context"
import { ApplicationTabHandle } from "./application-tab-handle"
import { useApplicationEditorTab } from "./use-application-editor-tab"
import { ApplicationEditorTabSkeleton } from "./application-editor-skeleton"

interface ApplicationExpertConfigProps {
  initialExpertConfig?: ApplicationExpertConfigType
  applicationName?: string
  namespace?: string
  onSaved?: (expertConfig: ApplicationExpertConfigType) => void
}

export const ApplicationExpertConfig = forwardRef<ApplicationTabHandle, ApplicationExpertConfigProps>(function ApplicationExpertConfig({
  initialExpertConfig,
  applicationName,
  namespace,
  onSaved,
}: ApplicationExpertConfigProps, ref) {
  const form = useForm<ApplicationExpertConfigFormValues>({
    resolver: zodResolver(applicationExpertConfigSchema),
    defaultValues: {
      environmentConfigs: initialExpertConfig?.environmentConfigs ?? [],
    },
    mode: "onChange",
  })

  const { control } = form
  const { fields, replace } = useFieldArray({
    control,
    name: "environmentConfigs",
  })

  const [activeTab, setActiveTab] = useState<string | undefined>(undefined)
  const [isSaving, setIsSaving] = useState(false)
  const [envsLoading, setEnvsLoading] = useState(!!(namespace && applicationName))
  const { t } = useLanguage()

  const buildSnapshot = useCallback((values: ApplicationExpertConfigFormValues = form.getValues()) => JSON.stringify({
    environmentConfigs: (values.environmentConfigs ?? []).map((config) => ({
      environmentName: config.environmentName,
      serviceAccountName: config.serviceAccountName ?? "",
    })),
  }), [form])

  const handleEnvironmentsLoaded = (envs: ApplicationEnvironment[]) => {
    const currentConfigs = form.getValues("environmentConfigs") || []
    const newConfigs = envs.map((env) => {
      const existing = currentConfigs.find((c) => c.environmentName === env.environmentName)
      return existing || { environmentName: env.environmentName, serviceAccountName: "" }
    })
    replace(newConfigs)
    form.reset({ environmentConfigs: newConfigs })
    captureBaseline()

    if (newConfigs.length > 0 && !activeTab) {
      setActiveTab(newConfigs[0].environmentName)
    }
  }

  useEffect(() => {
    if (fields.length > 0 && !activeTab) {
      setActiveTab(fields[0].environmentName)
    }
  }, [fields, activeTab])

  async function submitForm(data: ApplicationExpertConfigFormValues) {
    if (!applicationName || !namespace) {
      toast.error(t("apps.expertConfig.noAppInfo"))
      return false
    }

    setIsSaving(true)
    try {
      await updateApplicationExpertConfig(namespace, applicationName, {
        environmentConfigs: data.environmentConfigs,
      })
      toast.success(t("apps.expertConfig.saveSuccess"))
      onSaved?.({
        namespace,
        applicationName,
        environmentConfigs: data.environmentConfigs,
      })
      form.reset(data)
      return true
    } catch (error) {
      console.error(error)
      toast.error(t("apps.expertConfig.saveError"))
      return false
    } finally {
      setIsSaving(false)
    }
  }

  const saveCurrentTab = async () => {
    let success = false
    await form.handleSubmit(async (data) => {
      success = await submitForm(data)
    })()
    return success
  }

  const { captureBaseline, handleSubmit } = useApplicationEditorTab({
    ref,
    form,
    isReady: !envsLoading,
    getSnapshot: buildSnapshot,
    onSave: saveCurrentTab,
    onSubmit: submitForm,
  })

  return (
    <>
      {envsLoading && <ApplicationEditorTabSkeleton />}
      <div className={envsLoading ? "hidden" : "w-full"}>
        <Form {...form}>
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="border rounded-lg overflow-hidden">
              <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
                <Wrench className="size-4 text-muted-foreground" />
                <span className="text-sm font-semibold">{t("apps.expertConfig.title")}</span>
              </div>
              <div className="flex flex-col gap-4 p-4">
                <ApplicationEnvironmentSelector
                  namespace={namespace}
                  applicationName={applicationName}
                  value={activeTab}
                  onValueChange={setActiveTab}
                  onEnvironmentsLoaded={handleEnvironmentsLoaded}
                  onLoadingChange={setEnvsLoading}
                  className="w-full"
                >
                  {fields.map((field, index) => (
                    <TabsContent key={field.id} value={field.environmentName}>
                      <SingleExpertEnvironmentConfig
                        index={index}
                        namespace={namespace}
                        applicationName={applicationName}
                        environmentName={field.environmentName}
                      />
                    </TabsContent>
                  ))}
                </ApplicationEnvironmentSelector>
              </div>
            </div>
            <div className="flex">
              <Button type="submit" disabled={isSaving}>
                {isSaving ? t("common.saving") : t("common.save")}
              </Button>
            </div>
          </form>
        </Form>
      </div>
    </>
  )
})

interface SingleExpertEnvironmentConfigProps {
  index: number
  namespace?: string
  applicationName?: string
  environmentName: string
}

function SingleExpertEnvironmentConfig({ index, namespace, applicationName, environmentName }: SingleExpertEnvironmentConfigProps) {
  const { control } = useFormContext<ApplicationExpertConfigFormValues>()
  const { t } = useLanguage()
  const [serviceAccounts, setServiceAccounts] = useState<string[]>([])
  const [saLoading, setSaLoading] = useState(false)

  useEffect(() => {
    if (!namespace || !environmentName) return
    let cancelled = false
    const load = async () => {
      setSaLoading(true)
      try {
        const res = await fetchServiceAccounts(namespace, environmentName)
        if (!cancelled) setServiceAccounts(res.data ?? [])
      } catch {
        if (!cancelled) setServiceAccounts([])
      } finally {
        if (!cancelled) setSaLoading(false)
      }
    }
    load()
    return () => { cancelled = true }
  }, [namespace, environmentName])

  const serviceAccountOptions = serviceAccounts.map((name) => ({ value: name, label: name }))

  return (
    <div className="flex flex-col gap-4">
      <FormField
        control={control}
        name={`environmentConfigs.${index}.serviceAccountName`}
        render={({ field }) => (
          <FormItem>
            <FormLabel className="flex items-center gap-1"><KeyRound className="size-3.5" />{t("apps.expertConfig.serviceAccount")}</FormLabel>
            <FormControl>
              <SelectWithSearch
                value={field.value ?? ""}
                onValueChange={field.onChange}
                options={serviceAccountOptions}
                disabled={saLoading}
                placeholder=""
                searchPlaceholder={t("apps.expertConfig.serviceAccountSearch")}
                emptyText={t("apps.expertConfig.serviceAccountEmpty")}
                className="w-64"
              />
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />
      <ExpertResourceViewer
        namespace={namespace}
        applicationName={applicationName}
        environmentName={environmentName}
      />
    </div>
  )
}

interface ExpertResourceViewerProps {
  namespace?: string
  applicationName?: string
  environmentName: string
}

function ExpertResourceViewer({ namespace, applicationName, environmentName }: ExpertResourceViewerProps) {
  const { t } = useLanguage()
  const { resolvedTheme } = useTheme()
  const editorTheme = resolvedTheme === "dark" ? "vs-dark" : "vs"
  const [resources, setResources] = useState<ApplicationResource[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [activeKind, setActiveKind] = useState<string>("")

  const loadResources = useCallback(async () => {
    if (!namespace || !applicationName || !environmentName) return
    setLoading(true)
    setError(false)
    try {
      const res = await getApplicationResources(namespace, applicationName, environmentName)
      setResources(res.data ?? [])
    } catch {
      setError(true)
      setResources([])
    } finally {
      setLoading(false)
    }
  }, [namespace, applicationName, environmentName])

  useEffect(() => {
    loadResources()
  }, [loadResources])

  const kinds = Array.from(new Set(resources.map((resource) => resource.kind)))

  useEffect(() => {
    if (kinds.length > 0 && !kinds.includes(activeKind)) {
      setActiveKind(kinds[0])
    }
  }, [kinds, activeKind])

  const activeContent = resources
    .filter((resource) => resource.kind === activeKind)
    .map((resource) => `# ===== ${resource.name} =====\n${resource.data.trimEnd()}`)
    .join("\n---\n")

  return (
    <div className="border rounded-md overflow-hidden">
      <div className="bg-muted px-3 py-1.5 text-xs text-muted-foreground border-b flex items-center justify-between">
        <span className="flex items-center gap-1"><FileCode className="size-3.5" />{t("apps.expertConfig.resources")}</span>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-6 px-2"
          onClick={loadResources}
          disabled={loading || !applicationName}
        >
          <RefreshCw className={`size-3.5 ${loading ? "animate-spin" : ""}`} />
          {t("apps.expertConfig.resourcesRefresh")}
        </Button>
      </div>
      {error ? (
        <div className="px-3 py-6 text-sm text-muted-foreground text-center">{t("apps.expertConfig.resourcesError")}</div>
      ) : loading ? (
        <div className="px-3 py-6 text-sm text-muted-foreground text-center">{t("common.loading")}</div>
      ) : resources.length === 0 ? (
        <div className="px-3 py-6 text-sm text-muted-foreground text-center">{t("apps.expertConfig.resourcesEmpty")}</div>
      ) : (
        <Tabs value={activeKind} onValueChange={setActiveKind}>
          <div className="border-b px-3 py-1.5">
            <TabsList>
              {kinds.map((kind) => (
                <TabsTrigger key={kind} value={kind}>{kind}</TabsTrigger>
              ))}
            </TabsList>
          </div>
          <div className="h-[480px]">
            <Editor
              height="100%"
              defaultLanguage="yaml"
              theme={editorTheme}
              path={`${environmentName}/${activeKind}`}
              value={activeContent}
              options={{
                readOnly: true,
                minimap: { enabled: false },
                lineNumbers: "on",
                scrollBeyondLastLine: false,
                automaticLayout: true,
                padding: { top: 10 },
              }}
            />
          </div>
        </Tabs>
      )}
    </div>
  )
}
