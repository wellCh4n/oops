"use client"

import { useState, useEffect } from "react"
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
import { Separator } from "@/components/ui/separator"
import { useForm, useFieldArray, useFormContext } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import Editor from "@monaco-editor/react"
import { ApplicationBuildFormValues, applicationBuildSchema } from "../schema"
import { TabsContent } from "@/components/ui/tabs"
import { ApplicationBuildEnvironmentConfig, ApplicationBuildConfig, ApplicationEnvironment } from "@/lib/api/types"
import { updateApplicationBuildEnvConfigs, updateApplicationBuildConfig } from "@/lib/api/applications"
import { GitBranch, FileCode, Box, Terminal } from "lucide-react"
import { toast } from "sonner"
import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import { Skeleton } from "@/components/ui/skeleton"
import { useTheme } from "next-themes"
import { useLanguage } from "@/contexts/language-context"

interface ApplicationBuildInfoProps {
  initialBuildConfig?: ApplicationBuildConfig
  initialEnvConfigs?: ApplicationBuildEnvironmentConfig[]
  applicationId?: string
  applicationName?: string
  namespace?: string
}

export function ApplicationBuildInfo({
  initialBuildConfig,
  initialEnvConfigs = [],
  applicationId,
  applicationName,
  namespace,
}: ApplicationBuildInfoProps) {
  const form = useForm<ApplicationBuildFormValues>({
    resolver: zodResolver(applicationBuildSchema),
    defaultValues: {
      repository: initialBuildConfig?.repository || "",
      dockerFile: initialBuildConfig?.dockerFile || "Dockerfile",
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
    replace(newConfigs)

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

  const handleSave = async (data: ApplicationBuildFormValues) => {
    if (!applicationId || !applicationName || !namespace) {
      toast.error(t("apps.build.noAppInfo"))
      return
    }

    setIsSaving(true)
    try {
      // 1. Save global build config
      const buildConfigPayload: ApplicationBuildConfig = {
        repository: data.repository,
        dockerFile: data.dockerFile,
        buildImage: data.buildImage,
        namespace,
        applicationName,
      }
      await updateApplicationBuildConfig(namespace, applicationName, buildConfigPayload)

      // 2. Save environment configs
      const envConfigs = data.environmentConfigs as ApplicationBuildEnvironmentConfig[]
      await updateApplicationBuildEnvConfigs(namespace, applicationName, envConfigs)

      toast.success(t("apps.build.saveSuccess"))
    } catch (error) {
      console.error(error)
      toast.error(t("apps.build.saveError"))
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(handleSave)}>
        <div className="flex flex-col gap-6">
          {/* Global Build Config Section */}
          <div className="flex flex-col gap-4">
            <h3 className="text-lg font-medium">{t("apps.build.sourceConfig")}</h3>
            <FormField
              control={form.control}
              name="repository"
              render={({ field }) => (
                <FormItem>
                  <FormLabel className="flex items-center gap-1"><GitBranch className="h-3.5 w-3.5" />{t("apps.build.repository")}</FormLabel>
                  <FormControl>
                    <Input placeholder={t("apps.build.repositoryPlaceholder")} {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <div className="grid grid-cols-2 gap-4">
              <FormField
                control={form.control}
                name="dockerFile"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel className="flex items-center gap-1"><FileCode className="h-3.5 w-3.5" />{t("apps.build.dockerfilePath")}</FormLabel>
                    <FormControl>
                      <Input placeholder="Dockerfile" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="buildImage"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel className="flex items-center gap-1"><Box className="h-3.5 w-3.5" />{t("apps.build.buildImage")}</FormLabel>
                    <FormControl>
                      <Input placeholder={t("apps.build.buildImagePlaceholder")} {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>
          </div>

          <Separator />
          <div className="flex flex-col gap-4">
            <h3 className="text-lg font-medium">{t("apps.build.envConfig")}</h3>
            <div className="w-full">
              {envsLoading && (
                <div className="flex flex-col gap-3">
                  <Skeleton className="h-9 w-64" />
                  <Skeleton className="h-32 w-full" />
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
                >
                  {fields.map((field, index) => (
                    <TabsContent key={field.id} value={field.environmentName}>
                      <SingleEnvironmentConfig index={index} />
                    </TabsContent>
                  ))}
                </ApplicationEnvironmentSelector>
              </div>
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
}

interface SingleEnvironmentConfigProps {
  index: number
}

function SingleEnvironmentConfig({ index }: SingleEnvironmentConfigProps) {
  const { control } = useFormContext<ApplicationBuildFormValues>()
  const { resolvedTheme } = useTheme()
  const editorTheme = resolvedTheme === "dark" ? "vs-dark" : "vs"
  const { t } = useLanguage()

  return (
    <div className="flex flex-col gap-4">
      <FormField
        control={control}
        name={`environmentConfigs.${index}.buildCommand`}
        render={({ field }) => (
          <FormItem>
            <FormLabel className="flex items-center gap-1"><Terminal className="h-3.5 w-3.5" />{t("apps.build.buildCommand")}</FormLabel>
            <FormControl>
              <div className="border rounded-md overflow-hidden">
                <div className="bg-muted px-3 py-1 text-xs text-muted-foreground border-b flex items-center">
                  <span>shell</span>
                </div>
                <div className="h-[200px]">
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
    </div>
  )
}
