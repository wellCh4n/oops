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
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { useForm, useFieldArray } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import Editor from "@monaco-editor/react"
import { ApplicationBuildFormValues, applicationBuildSchema } from "../schema"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ApplicationBuildEnvironmentConfig, ApplicationBuildConfig, ApplicationEnvironment } from "@/lib/api/types"
import { updateApplicationBuildEnvConfigs, updateApplicationBuildConfig } from "@/lib/api/applications"
import { GitBranch, FileCode, Box, Terminal, PackageSearch, Settings2, Hammer, FolderOpen, SlidersHorizontal } from "lucide-react"
import { toast } from "sonner"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import { Skeleton } from "@/components/ui/skeleton"
import { useTheme } from "next-themes"
import { useLanguage } from "@/contexts/language-context"
import { useFeaturesStore } from "@/store/features"
import { ApplicationTabHandle } from "./application-tab-handle"
import { useApplicationEditorTab } from "./use-application-editor-tab"

interface ApplicationBuildInfoProps {
  initialBuildConfig?: ApplicationBuildConfig
  initialEnvConfigs?: ApplicationBuildEnvironmentConfig[]
  applicationId?: string
  applicationName?: string
  namespace?: string
  onSaved?: (
    buildConfig: ApplicationBuildConfig,
    envConfigs: ApplicationBuildEnvironmentConfig[]
  ) => void
}

export const ApplicationBuildInfo = forwardRef<ApplicationTabHandle, ApplicationBuildInfoProps>(function ApplicationBuildInfo({
  initialBuildConfig,
  initialEnvConfigs = [],
  applicationId,
  applicationName,
  namespace,
  onSaved,
}: ApplicationBuildInfoProps, ref) {
  const form = useForm<ApplicationBuildFormValues>({
    resolver: zodResolver(applicationBuildSchema),
    defaultValues: {
      sourceType: initialBuildConfig?.sourceType || "GIT",
      repository: initialBuildConfig?.repository || "",
      dockerFileConfig: initialBuildConfig?.dockerFileConfig || { type: "BUILTIN", path: "Dockerfile" },
      buildImage: initialBuildConfig?.buildImage || "",
      environmentConfigs: initialEnvConfigs,
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
  const sourceType = form.watch("sourceType")
  const dockerFileConfigValue = form.watch("dockerFileConfig")
  const objectStorageEnabled = useFeaturesStore((s) => s.features.objectStorage)
  const featuresLoaded = useFeaturesStore((s) => s.loaded)

  const buildSnapshot = useCallback((values: ApplicationBuildFormValues = form.getValues()) => JSON.stringify({
    sourceType: values.sourceType,
    repository: values.repository ?? "",
    dockerFileConfig: values.dockerFileConfig ?? { type: "BUILTIN", path: "Dockerfile" },
    buildImage: values.buildImage ?? "",
    environmentConfigs: (values.environmentConfigs ?? []).map((config) => ({
      environmentName: config.environmentName,
      buildCommand: config.buildCommand ?? "",
    })),
  }), [form])

  // If object storage is disabled but current source type is ZIP, fall back to GIT.
  // Gate on featuresLoaded to avoid downgrading ZIP apps before the async features API resolves.
  useEffect(() => {
    if (featuresLoaded && !objectStorageEnabled && sourceType === "ZIP") {
      form.setValue("sourceType", "GIT", { shouldValidate: true })
    }
  }, [featuresLoaded, objectStorageEnabled, sourceType, form])

  const handleEnvironmentsLoaded = (envs: ApplicationEnvironment[]) => {
    // Sync form fields with fetched environments
    const currentConfigs = form.getValues("environmentConfigs") || []
    const newConfigs = envs.map((env) => {
      const existing = currentConfigs.find(
        (c) => c.environmentName === env.environmentName
      )
      return (
        existing || {
          environmentName: env.environmentName,
          buildCommand: "",
        }
      )
    })
    const nextValues = {
      ...form.getValues(),
      environmentConfigs: newConfigs,
    }
    replace(newConfigs)
    form.reset(nextValues)
    captureBaseline()

    // Set active tab if not set
    if (newConfigs.length > 0 && !activeTab) {
      setActiveTab(newConfigs[0].environmentName)
    }
  }

  // Initialize activeTab
  useEffect(() => {
    if (fields.length > 0 && !activeTab) {
      setActiveTab(fields[0].environmentName)
    }
  }, [fields, activeTab])

  async function submitForm(data: ApplicationBuildFormValues) {
    if (!applicationId || !applicationName || !namespace) {
      toast.error(t("apps.build.noAppInfo"))
      return false
    }

    setIsSaving(true)
    try {
      // 1. Save global build config
      const buildConfigPayload: ApplicationBuildConfig = {
        sourceType: data.sourceType,
        repository: data.sourceType === "GIT" ? data.repository : undefined,
        dockerFileConfig: data.dockerFileConfig,
        buildImage: data.buildImage,
        namespace,
        applicationName,
      }
      await updateApplicationBuildConfig(namespace, applicationName, buildConfigPayload)

      // 2. Save environment configs
      const envConfigs = data.environmentConfigs as ApplicationBuildEnvironmentConfig[]
      await updateApplicationBuildEnvConfigs(namespace, applicationName, envConfigs)

      toast.success(t("apps.build.saveSuccess"))
      onSaved?.(buildConfigPayload, envConfigs)
      form.reset(data)
      return true
    } catch (error) {
      console.error(error)
      toast.error(t("apps.build.saveError"))
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

  const { resolvedTheme } = useTheme()
  const editorTheme = resolvedTheme === "dark" ? "vs-dark" : "vs"
  const activeEnvironmentIndex = fields.findIndex((f) => f.environmentName === activeTab)

  return (
    <Form {...form}>
      <form onSubmit={handleSubmit} className="w-full">
        <div className="flex flex-col gap-4">
          {/* Global Build Config Section */}
          <div className="border rounded-lg overflow-hidden">
            <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
              <Settings2 className="h-4 w-4 text-muted-foreground" />
              <span className="text-sm font-semibold">{t("apps.build.sourceConfig")}</span>
            </div>
            <div className="flex flex-col gap-4 p-4">
            <FormField
              control={form.control}
              name="sourceType"
              render={({ field }) => (
                <FormItem>
                  <FormLabel className="flex items-center gap-1"><PackageSearch className="h-3.5 w-3.5" />{t("apps.build.sourceType")}</FormLabel>
                  <FormControl>
                    <Tabs value={field.value} onValueChange={field.onChange}>
                      <TabsList className="justify-start h-auto flex-wrap">
                        <TabsTrigger value="GIT" className="px-6 cursor-pointer">
                          {t("apps.build.sourceGit")}
                        </TabsTrigger>
                        {objectStorageEnabled && (
                          <TabsTrigger value="ZIP" className="px-6 cursor-pointer">
                            {t("apps.build.sourceZip")}
                          </TabsTrigger>
                        )}
                      </TabsList>
                    </Tabs>
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            {sourceType === "GIT" ? (
              <FormField
                control={form.control}
                name="repository"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel className="flex items-center gap-1"><GitBranch className="h-3.5 w-3.5" />{t("apps.build.repository")}</FormLabel>
                    <FormControl>
                      <Input autoComplete="off" placeholder={t("apps.build.repositoryPlaceholder")} {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            ) : (
              <div className="text-sm text-muted-foreground">{t("apps.build.zipConfiguredInPublish")}</div>
            )}

            <div className="grid gap-2">
              <Label className="flex items-center gap-1">
                <FileCode className="h-3.5 w-3.5" />
                {t("apps.build.dockerfile")}
              </Label>
              <div className="grid gap-3 border rounded-md p-3">
              <div className="flex items-center gap-1 text-sm font-medium">
                <SlidersHorizontal className="h-3.5 w-3.5 text-muted-foreground" />
                {t("apps.build.dockerfileType")}
              </div>
              <FormField
                control={form.control}
                name="dockerFileConfig.type"
                render={({ field }) => (
                  <FormItem>
                    <FormControl>
                      <Tabs value={field.value || "BUILTIN"} onValueChange={(value) => {
                        field.onChange(value)
                        const current = form.getValues("dockerFileConfig")
                        form.setValue("dockerFileConfig", {
                          type: value as "BUILTIN" | "USER",
                          path: value === "BUILTIN" ? (current?.path || "Dockerfile") : current?.path,
                          content: current?.content,
                        })
                      }}>
                        <TabsList className="justify-start h-auto flex-wrap">
                          <TabsTrigger value="BUILTIN" className="px-6 cursor-pointer">
                            {t("apps.build.dockerfileBuiltin")}
                          </TabsTrigger>
                          <TabsTrigger value="USER" className="px-6 cursor-pointer">
                            {t("apps.build.dockerfileUser")}
                          </TabsTrigger>
                        </TabsList>
                      </Tabs>
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <div className={(dockerFileConfigValue?.type || "BUILTIN") === "BUILTIN" ? "" : "hidden"}>
                <FormField
                  control={form.control}
                  name="dockerFileConfig.path"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel className="flex items-center gap-1"><FolderOpen className="h-3.5 w-3.5" />{t("apps.build.dockerfilePath")}</FormLabel>
                      <FormControl>
                        <Input autoComplete="off" placeholder="Dockerfile" {...field} value={field.value || ""} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
              <div className={(dockerFileConfigValue?.type || "BUILTIN") === "USER" ? "" : "hidden"}>
                <FormField
                  control={form.control}
                  name="dockerFileConfig.content"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel className="flex items-center gap-1"><FileCode className="h-3.5 w-3.5" />{t("apps.build.dockerfileContent")}</FormLabel>
                      <FormControl>
                        <div className="border rounded-md overflow-hidden">
                          <div className="bg-muted px-3 py-1 text-xs text-muted-foreground border-b flex items-center">
                            <span>dockerfile</span>
                          </div>
                          <div className="h-[120px]">
                            <Editor
                              height="100%"
                              defaultLanguage="dockerfile"
                              theme={editorTheme}
                              value={field.value || ""}
                              onChange={field.onChange}
                              options={{
                                minimap: { enabled: false },
                                lineNumbers: "on",
                                scrollBeyondLastLine: false,
                                automaticLayout: true,
                                padding: { top: 10 },
                              }}
                            />
                          </div>
                        </div>
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
            </div>
            </div>
            </div>
          </div>

          <div className="border rounded-lg overflow-hidden">
            <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
              <Hammer className="h-4 w-4 text-muted-foreground" />
              <span className="text-sm font-semibold">{t("apps.build.envConfig")}</span>
            </div>
            <div className="flex flex-col gap-4 p-4">
              <FormField
                control={form.control}
                name="buildImage"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel className="flex items-center gap-1"><Box className="h-3.5 w-3.5" />{t("apps.build.buildImage")}</FormLabel>
                    <FormControl>
                      <Input autoComplete="off" placeholder={t("apps.build.buildImagePlaceholder")} {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <div className="grid gap-2">
                <Label className="flex items-center gap-1">
                  <Terminal className="h-3.5 w-3.5" />
                  {t("apps.build.buildCommand")}
                </Label>
                <div className="w-full">
                  {envsLoading && (
                    <div className="flex flex-col gap-3">
                      <Skeleton className="h-9 w-64" />
                    </div>
                  )}
                  <div className={envsLoading ? "hidden" : ""}>
                    <ApplicationEnvironmentSelector
                      namespace={namespace}
                      applicationName={applicationName}
                      value={activeTab}
                      onValueChange={setActiveTab}
                      onEnvironmentsLoaded={handleEnvironmentsLoaded}
                      onLoadingChange={setEnvsLoading}
                      className="w-full"
                    />
                  </div>
                </div>
              </div>

              {activeEnvironmentIndex >= 0 && (
                <FormField
                  control={form.control}
                  name={`environmentConfigs.${activeEnvironmentIndex}.buildCommand`}
                  render={({ field }) => (
                    <FormItem>
                      <FormControl>
                        <div className="border rounded-md overflow-hidden">
                          <div className="bg-muted px-3 py-1 text-xs text-muted-foreground border-b flex items-center">
                            <span>shell</span>
                          </div>
                          <div className="h-[120px]">
                            <Editor
                              height="100%"
                              defaultLanguage="shell"
                              theme={editorTheme}
                              value={field.value}
                              onChange={field.onChange}
                              options={{
                                minimap: { enabled: false },
                                lineNumbers: "on",
                                scrollBeyondLastLine: false,
                                automaticLayout: true,
                                padding: { top: 10 },
                              }}
                            />
                          </div>
                        </div>
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              )}
          </div>
        </div>

        <div className="flex">
          <Button type="submit" disabled={isSaving}>
                {isSaving ? t("common.saving") : t("common.save")}
             </Button>
          </div>
        </div>
      </form>
    </Form>
  )
})
