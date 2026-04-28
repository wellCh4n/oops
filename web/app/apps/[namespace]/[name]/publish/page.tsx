"use client"

import { use, useState, useEffect, useRef } from "react"
import { useRouter } from "next/navigation"
import { Button } from "@/components/ui/button"
import { Rocket, Upload, ExternalLink, Check } from "lucide-react"
import { toast } from "sonner"
import { createApplicationBuildSourceUpload, getApplication, getApplicationBuildConfig, getApplicationEnvironments, deployApplication, getLastSuccessfulPipeline } from "@/lib/api/applications"
import { Application, ApplicationEnvironment, ApplicationSourceType, DeployMode, DeployStrategyParam, LastSuccessfulPipelineInfo } from "@/lib/api/types"
import { Label } from "@/components/ui/label"
import { Input } from "@/components/ui/input"
import { useLanguage } from "@/contexts/language-context"
import { ContentPage } from "@/components/content-page"
import Link from "next/link"
import { useRecentAppStore } from "@/store/recent-app"
import { useFeaturesStore } from "@/store/features"
import { cn } from "@/lib/utils"

interface PageProps {
  params: Promise<{
    namespace: string
    name: string
  }>
}

export default function PublishPage({ params }: PageProps) {
  const router = useRouter()
  // Unwrap params using React.use()
  const { namespace, name } = use(params)

  const [application, setApplication] = useState<Application | null>(null)
  const [environments, setEnvironments] = useState<ApplicationEnvironment[]>([])
  const [selectedEnv, setSelectedEnv] = useState<string>("")
  const [sourceType, setSourceType] = useState<ApplicationSourceType>("GIT")
  const [branch, setBranch] = useState<string>("main")
  const [publishRepository, setPublishRepository] = useState<string>("")
  const [lastSuccessfulPipeline, setLastSuccessfulPipeline] = useState<LastSuccessfulPipelineInfo | null>(null)
  const [deployMode, setDeployMode] = useState<DeployMode>("MANUAL")
  const [loading, setLoading] = useState(false)
  const [isUploading, setIsUploading] = useState(false)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const envInitialized = useRef(false)
  const { t } = useLanguage()
  const { setRecentApp } = useRecentAppStore()
  const objectStorageEnabled = useFeaturesStore((s) => s.features.objectStorage)

  const normalizeText = (value: string | null | undefined) => value ?? ""

  useEffect(() => {
    const fetchData = async () => {
      try {
        const [appRes, envRes, lastPipelineRes, buildConfigRes] = await Promise.all([
          getApplication(namespace, name),
          getApplicationEnvironments(namespace, name),
          getLastSuccessfulPipeline(namespace, name),
          getApplicationBuildConfig(namespace, name)
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
        if (envRes.data) {
          setEnvironments(envRes.data)
          if (!envInitialized.current && envRes.data.length > 0) {
            const firstEnv = envRes.data[0].environmentName
            if (firstEnv) {
              setSelectedEnv(firstEnv)
              envInitialized.current = true
            }
          }
        }
        if (lastPipelineRes.data) {
          setLastSuccessfulPipeline(lastPipelineRes.data)
          setBranch(normalizeText(lastPipelineRes.data.branch) || "main")
          setDeployMode(lastPipelineRes.data.deployMode)
          setPublishRepository(normalizeText(lastPipelineRes.data.publishRepository))
        }
        if (buildConfigRes.data?.sourceType) {
          setSourceType(buildConfigRes.data.sourceType)
        }
      } catch {
        toast.error(t("apps.publish.fetchError"))
      }
    }
    fetchData()
  }, [namespace, name, t, setRecentApp])

  const handlePublish = async () => {
    if (!selectedEnv) {
      toast.error(t("apps.publish.noEnvError"))
      return
    }
    if (sourceType === "ZIP" && !publishRepository.trim()) {
      toast.error(t("apps.publish.zipRequired"))
      return
    }

    setLoading(true)
    try {
      const strategy: DeployStrategyParam = sourceType === "ZIP"
        ? { type: "ZIP", repository: publishRepository.trim() }
        : { type: "GIT", branch: branch.trim() || "main" }
      const res = await deployApplication(
        namespace,
        name,
        {
          environment: selectedEnv,
          deployMode,
          strategy,
        }
      )
      if (res.success) {
        toast.success(t("apps.publish.submitSuccessPrefix") + selectedEnv)
        // Assuming the backend returns the pipeline ID in res.data
        if (res.data) {
             router.push(`/apps/${namespace}/${name}/pipelines/${res.data}`)
        } else {
             router.push("/apps")
        }
      } else {
        toast.error(res.message || t("apps.publish.submitError"))
      }
    } catch {
      toast.error(t("apps.publish.submitError"))
    } finally {
      setLoading(false)
    }
  }

  const handleZipUpload = async (file?: File) => {
    if (!file) return
    if (!file.name.toLowerCase().endsWith(".zip")) {
      toast.error(t("apps.build.zipOnly"))
      return
    }

    setIsUploading(true)
    try {
      const uploadRes = await createApplicationBuildSourceUpload(namespace, name, {
        fileName: file.name,
        fileSize: file.size,
        contentType: file.type || "application/zip",
      })
      if (!uploadRes.success || !uploadRes.data) {
        toast.error(uploadRes.message || t("apps.build.uploadError"))
        return
      }

      const headers = new Headers()
      Object.entries(uploadRes.data.headers || {}).forEach(([key, value]) => {
        headers.set(key, value)
      })

      const putRes = await fetch(uploadRes.data.uploadUrl, {
        method: "PUT",
        headers,
        body: file,
      })
      if (!putRes.ok) {
        throw new Error("Upload failed")
      }

      const objectKey = normalizeText(uploadRes.data.objectKey)
      if (!objectKey) {
        toast.error(t("apps.build.uploadError"))
        return
      }

      setPublishRepository(objectKey)
      toast.success(t("apps.build.uploadSuccess"))
    } catch (error) {
      console.error(error)
      toast.error(t("apps.build.uploadError"))
    } finally {
      if (fileInputRef.current) {
        fileInputRef.current.value = ""
      }
      setIsUploading(false)
    }
  }

  if (!application) {
    return <ContentPage title={name}>Loading...</ContentPage>
  }

  return (
    <ContentPage title={application.name}>
      <div className="rounded-md border bg-card p-4">
        <div className="space-y-4">
        <div className="grid gap-2">
          <div className="text-sm font-medium">{t("apps.publish.appName")}</div>
          <div className="text-sm text-muted-foreground">{application.name}</div>
        </div>
        <div className="grid gap-2">
          <div className="text-sm font-medium">{t("apps.publish.namespace")}</div>
          <div className="text-sm text-muted-foreground">{application.namespace}</div>
        </div>
        <div className="grid gap-2">
          <Label>{t("apps.publish.env")}</Label>
          {environments.length > 0 ? (
            <div className="flex flex-wrap gap-3">
              {environments.map(env => {
                const selected = selectedEnv === env.environmentName
                return (
                  <div
                    key={env.environmentName}
                    role="button"
                    tabIndex={0}
                    onClick={() => setSelectedEnv(env.environmentName)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault()
                        setSelectedEnv(env.environmentName)
                      }
                    }}
                    className={cn(
                      "rounded-lg border p-3 flex items-center justify-between cursor-pointer transition-colors select-none gap-3 min-w-[12rem]",
                      selected
                        ? "border-primary bg-primary/5 text-primary"
                        : "border-border hover:bg-accent/50"
                    )}
                  >
                    <span className="text-sm font-medium">{env.environmentName}</span>
                    {selected ? (
                      <div className="flex h-5 w-5 items-center justify-center rounded-full bg-primary text-primary-foreground">
                        <Check className="h-3 w-3" />
                      </div>
                    ) : (
                      <div className="h-5 w-5 rounded-full border border-muted-foreground/30" />
                    )}
                  </div>
                )
              })}
            </div>
          ) : (
            <p className="text-sm text-destructive">
              {t("apps.publish.noEnvPrefix")}
              <Link
                href={`/apps/${namespace}/${name}?tab=app-info`}
                className="inline-flex items-center gap-0.5 text-primary ml-1 mr-1"
              >
                <span className="hover:underline">{t("apps.publish.noEnvLink")}</span>
                <ExternalLink className="h-3 w-3" />
              </Link>
              {t("apps.publish.noEnvSuffix")}
            </p>
          )}
        </div>

        {sourceType === "GIT" && (
          <div className="grid gap-2">
            <Label htmlFor="branch">
              {t("apps.publish.branch")}
              {lastSuccessfulPipeline?.branch && (
                <button
                  type="button"
                  onClick={() => {
                    setBranch(lastSuccessfulPipeline.branch || "main")
                  }}
                  className="ml-2 text-sm text-primary hover:text-primary/80 cursor-pointer"
                >
                  {t("apps.publish.lastBranch")}{lastSuccessfulPipeline.branch}
                </button>
              )}
            </Label>
            <Input
              id="branch"
              value={branch}
              onChange={(e) => setBranch(e.target.value)}
              placeholder="main"
            />
          </div>
        )}

        {sourceType === "ZIP" && (
          <div className="grid gap-2">
            <Label htmlFor="publish-repository">{t("apps.publish.zipUrl")}</Label>
            <div className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center">
              <div className="min-w-0">
                <Input
                  id="publish-repository"
                  autoComplete="off"
                  className="w-full min-w-0"
                  value={publishRepository}
                  onChange={(e) => setPublishRepository(e.target.value)}
                  placeholder={t("apps.publish.zipUrlPlaceholder")}
                />
              </div>
              {objectStorageEnabled && (
                <>
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept=".zip,application/zip"
                    className="hidden"
                    onChange={(event) => void handleZipUpload(event.target.files?.[0])}
                  />
                  <Button
                    type="button"
                    variant="outline"
                    className="w-full shrink-0 sm:w-auto"
                    disabled={isUploading}
                    onClick={() => fileInputRef.current?.click()}
                  >
                    <Upload className="h-4 w-4" />
                    {isUploading ? t("apps.build.uploading") : t("apps.build.uploadZip")}
                  </Button>
                </>
              )}
            </div>
          </div>
        )}

        <div className="grid gap-2">
          <Label>
            {t("apps.publish.mode")}
            {lastSuccessfulPipeline && (
              <button
                type="button"
                onClick={() => {
                  setDeployMode(lastSuccessfulPipeline.deployMode)
                }}
                className="ml-2 text-sm text-primary hover:text-primary/80 cursor-pointer"
              >
                {t("apps.publish.lastDeployMode")}{lastSuccessfulPipeline.deployMode === "MANUAL" ? t("apps.publish.modeManual") : t("apps.publish.modeImmediate")}
              </button>
            )}
          </Label>
          <div className="flex flex-wrap gap-3">
            {(["MANUAL", "IMMEDIATE"] as DeployMode[]).map(mode => {
              const selected = deployMode === mode
              return (
                <div
                  key={mode}
                  role="button"
                  tabIndex={0}
                  onClick={() => setDeployMode(mode)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" || e.key === " ") {
                      e.preventDefault()
                      setDeployMode(mode)
                    }
                  }}
                  className={cn(
                    "rounded-lg border p-3 flex items-center justify-between cursor-pointer transition-colors select-none gap-3 min-w-[12rem]",
                    selected
                      ? "border-primary bg-primary/5 text-primary"
                      : "border-border hover:bg-accent/50"
                  )}
                >
                  <span className="text-sm font-medium">
                    {mode === "MANUAL" ? t("apps.publish.modeManual") : t("apps.publish.modeImmediate")}
                  </span>
                  {selected ? (
                    <div className="flex h-5 w-5 items-center justify-center rounded-full bg-primary text-primary-foreground">
                      <Check className="h-3 w-3" />
                    </div>
                  ) : (
                    <div className="h-5 w-5 rounded-full border border-muted-foreground/30" />
                  )}
                </div>
              )
            })}
          </div>
        </div>

        <div className="pt-2">
          <Button onClick={handlePublish} disabled={loading || !selectedEnv}>
            <Rocket className="h-4 w-4" />
            {loading ? t("apps.publish.submitting") : deployMode === "MANUAL" ? t("apps.publish.submitBuild") : t("apps.publish.confirmDeploy")}
          </Button>
        </div>
        </div>
      </div>
    </ContentPage>
  )
}
