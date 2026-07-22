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
import { useForm, useFieldArray, useFormContext, useWatch } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { ApplicationExpertConfigFormValues, applicationExpertConfigSchema } from "../schema"
import { TabsContent } from "@/components/ui/tabs"
import { ApplicationExpertConfig as ApplicationExpertConfigType, ApplicationEnvironment, NodeStatus } from "@/lib/api/types"
import { updateApplicationExpertConfig } from "@/lib/api/applications"
import { fetchServiceAccounts } from "@/lib/api/service-accounts"
import { fetchNodes } from "@/lib/api/nodes"
import { fetchCronNextRuns } from "@/lib/api/cron"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import { MultiSelectWithSearch } from "@/components/ui/multi-select-with-search"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { CronScheduleBuilder } from "./cron-schedule-builder"
import { Gauge, KeyRound, Server, Timer, Wrench } from "lucide-react"
import { toast } from "sonner"
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
      priority: config.priority || "NORMAL",
      scheduledRestartEnabled: config.scheduledRestartEnabled ?? false,
      scheduledRestartCron: config.scheduledRestartCron ?? "",
      nodeNames: [...(config.nodeNames ?? [])].sort(),
    })),
  }), [form])

  const handleEnvironmentsLoaded = (envs: ApplicationEnvironment[]) => {
    const currentConfigs = form.getValues("environmentConfigs") || []
    const newConfigs = envs.map((env) => {
      const existing = currentConfigs.find((c) => c.environmentName === env.environmentName)
      return existing || {
        environmentName: env.environmentName,
        serviceAccountName: "",
        priority: "NORMAL",
        scheduledRestartEnabled: false,
        scheduledRestartCron: "",
        nodeNames: [],
      }
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
  environmentName: string
}

function SingleExpertEnvironmentConfig({ index, namespace, environmentName }: SingleExpertEnvironmentConfigProps) {
  const { control } = useFormContext<ApplicationExpertConfigFormValues>()
  const { t, locale } = useLanguage()
  const [serviceAccounts, setServiceAccounts] = useState<string[]>([])
  const [saLoading, setSaLoading] = useState(false)
  const [nodes, setNodes] = useState<NodeStatus[]>([])
  const [nodesLoading, setNodesLoading] = useState(false)

  const restartEnabled = useWatch({ control, name: `environmentConfigs.${index}.scheduledRestartEnabled` })
  const restartCron = useWatch({ control, name: `environmentConfigs.${index}.scheduledRestartCron` })
  const [nextRun, setNextRun] = useState<string | null>(null)
  const [cronInvalid, setCronInvalid] = useState(false)

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

  useEffect(() => {
    if (!environmentName) return
    let cancelled = false
    const load = async () => {
      setNodesLoading(true)
      try {
        const res = await fetchNodes(environmentName)
        if (!cancelled) setNodes(res.data ?? [])
      } catch {
        if (!cancelled) setNodes([])
      } finally {
        if (!cancelled) setNodesLoading(false)
      }
    }
    load()
    return () => { cancelled = true }
  }, [environmentName])

  useEffect(() => {
    if (!restartEnabled || !restartCron?.trim()) {
      setNextRun(null)
      setCronInvalid(false)
      return
    }
    let cancelled = false
    const timer = setTimeout(async () => {
      try {
        const res = await fetchCronNextRuns(restartCron.trim(), 1)
        if (cancelled) return
        if (res.success && res.data && res.data.length > 0) {
          setNextRun(res.data[0])
          setCronInvalid(false)
        } else {
          setNextRun(null)
          setCronInvalid(true)
        }
      } catch {
        if (!cancelled) {
          setNextRun(null)
          setCronInvalid(true)
        }
      }
    }, 300)
    return () => { cancelled = true; clearTimeout(timer) }
  }, [restartEnabled, restartCron])

  const serviceAccountOptions = serviceAccounts.map((name) => ({ value: name, label: name }))
  // Node affinity matches on the kubernetes.io/hostname label (node.hostname), which can differ from
  // the display name (node.name) when kubelet was started with --hostname-override. Bind on hostname,
  // show name.
  const nodeOptions = nodes.map((node) => ({
    value: node.hostname,
    label: node.name,
    description: [node.internalIP, node.cpu && `CPU ${node.cpu}`, node.memory && `内存 ${node.memory}`].filter(Boolean).join(" · "),
  }))

  return (
    <div className="flex flex-col gap-4">
      <FormField
        control={control}
        name={`environmentConfigs.${index}.priority`}
        render={({ field }) => (
          <FormItem>
            <FormLabel className="flex items-center gap-1"><Gauge className="size-3.5" />{t("apps.expertConfig.priority")}</FormLabel>
            <FormControl>
              <Select value={field.value || "NORMAL"} onValueChange={field.onChange}>
                <SelectTrigger className="w-64">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="HIGH">{t("apps.expertConfig.priorityHigh")}</SelectItem>
                  <SelectItem value="NORMAL">{t("apps.expertConfig.priorityNormal")}</SelectItem>
                  <SelectItem value="LOW">{t("apps.expertConfig.priorityLow")}</SelectItem>
                </SelectContent>
              </Select>
            </FormControl>
            <p className="text-xs text-muted-foreground">{t("apps.expertConfig.priorityHint")}</p>
            <FormMessage />
          </FormItem>
        )}
      />
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
      <FormField
        control={control}
        name={`environmentConfigs.${index}.nodeNames`}
        render={({ field }) => {
          const selected = field.value ?? []
          return (
            <FormItem>
              <FormLabel className="flex items-center gap-1"><Server className="size-3.5" />{t("apps.expertConfig.nodeAffinity")}</FormLabel>
              <FormControl>
                <MultiSelectWithSearch
                  values={selected}
                  onValuesChange={field.onChange}
                  options={nodeOptions}
                  disabled={nodesLoading}
                  placeholder={t("apps.expertConfig.nodeAffinityPlaceholder")}
                  searchPlaceholder={t("apps.expertConfig.nodeAffinitySearch")}
                  emptyText={t("apps.expertConfig.nodeAffinityEmpty")}
                  className="w-full max-w-2xl"
                />
              </FormControl>
              <p className="text-xs text-muted-foreground">{t("apps.expertConfig.nodeAffinityHint")}</p>
              <FormMessage />
            </FormItem>
          )
        }}
      />
      <FormField
        control={control}
        name={`environmentConfigs.${index}.scheduledRestartEnabled`}
        render={({ field }) => (
          <FormItem>
            <div className="flex items-center gap-2">
              <FormLabel className="flex items-center gap-1"><Timer className="size-3.5" />{t("apps.expertConfig.scheduledRestart")}</FormLabel>
              <FormControl>
                <Switch
                  checked={!!field.value}
                  onCheckedChange={field.onChange}
                  className="cursor-pointer"
                  aria-label={t("apps.expertConfig.scheduledRestart")}
                />
              </FormControl>
            </div>
            <p className="text-xs text-muted-foreground">{t("apps.expertConfig.scheduledRestartHint")}</p>
            <FormMessage />
          </FormItem>
        )}
      />
      {restartEnabled && (
        <FormField
          control={control}
          name={`environmentConfigs.${index}.scheduledRestartCron`}
          render={({ field }) => (
            <FormItem>
              <FormLabel>{t("apps.expertConfig.scheduledRestartCron")}</FormLabel>
              <FormControl>
                <CronScheduleBuilder
                  value={field.value ?? ""}
                  onChange={field.onChange}
                  locale={locale}
                  t={t}
                />
              </FormControl>
              {cronInvalid ? (
                <p className="text-xs text-destructive">{t("apps.expertConfig.scheduledRestartCronInvalid")}</p>
              ) : nextRun ? (
                <p className="text-xs text-muted-foreground">{t("apps.expertConfig.scheduledRestartNextRun")}: {nextRun}</p>
              ) : null}
              <FormMessage />
            </FormItem>
          )}
        />
      )}
    </div>
  )
}
