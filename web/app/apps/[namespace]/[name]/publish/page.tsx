"use client"

import { use, useState, useEffect } from "react"
import { useRouter } from "next/navigation"
import { Button } from "@/components/ui/button"
import { Rocket, ExternalLink } from "lucide-react"
import { toast } from "sonner"
import { getApplication, getApplicationEnvironments, deployApplication, getLastSuccessfulPipeline } from "@/lib/api/applications"
import { Application, ApplicationEnvironment, DeployMode, LastSuccessfulPipelineInfo } from "@/lib/api/types"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { Label } from "@/components/ui/label"
import { Input } from "@/components/ui/input"
import { useLanguage } from "@/contexts/language-context"
import { ContentPage } from "@/components/content-page"
import Link from "next/link"

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
  const [branch, setBranch] = useState<string>("main")
  const [lastSuccessfulPipeline, setLastSuccessfulPipeline] = useState<LastSuccessfulPipelineInfo | null>(null)
  const [deployMode, setDeployMode] = useState<DeployMode>("MANUAL")
  const [loading, setLoading] = useState(false)
  const { t } = useLanguage()

  useEffect(() => {
    const fetchData = async () => {
      try {
        const [appRes, envRes, lastPipelineRes] = await Promise.all([
          getApplication(namespace, name),
          getApplicationEnvironments(namespace, name),
          getLastSuccessfulPipeline(namespace, name)
        ])
        
        if (appRes.data) {
          setApplication(appRes.data)
        }
        if (envRes.data) {
          setEnvironments(envRes.data)
          if (envRes.data.length > 0) {
            const firstEnv = envRes.data[0].environmentName
            if (firstEnv) {
              setSelectedEnv(firstEnv)
            }
          }
        }
        if (lastPipelineRes.data) {
          setLastSuccessfulPipeline(lastPipelineRes.data)
          setBranch(lastPipelineRes.data.branch)
          setDeployMode(lastPipelineRes.data.deployMode)
        }
      } catch {
        toast.error(t("apps.publish.fetchError"))
      }
    }
    fetchData()
  }, [namespace, name, t])

  const handlePublish = async () => {
    if (!selectedEnv) {
      toast.error(t("apps.publish.noEnvError"))
      return
    }

    setLoading(true)
    try {
      const res = await deployApplication(namespace, name, selectedEnv, branch.trim() || "main", deployMode)
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
          <RadioGroup value={selectedEnv} onValueChange={setSelectedEnv} className="flex flex-row gap-6">
            {environments.map(env => (
              <div className="flex items-center space-x-2" key={env.environmentName}>
                <RadioGroupItem value={env.environmentName} id={env.environmentName} />
                <Label htmlFor={env.environmentName}>{env.environmentName}</Label>
              </div>
            ))}
          </RadioGroup>
          {environments.length === 0 && (
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

        <div className="grid gap-2">
          <Label htmlFor="branch">
            {t("apps.publish.branch")}
            {lastSuccessfulPipeline && (
              <button
                type="button"
                onClick={() => {
                  setBranch(lastSuccessfulPipeline.branch)
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
          <RadioGroup value={deployMode} onValueChange={(v) => setDeployMode(v as DeployMode)} className="flex flex-row gap-6">
            <div className="flex items-center space-x-2">
              <RadioGroupItem value="MANUAL" id="mode-manual" />
              <Label htmlFor="mode-manual">{t("apps.publish.modeManual")}</Label>
            </div>
            <div className="flex items-center space-x-2">
              <RadioGroupItem value="IMMEDIATE" id="mode-immediate" />
              <Label htmlFor="mode-immediate">{t("apps.publish.modeImmediate")}</Label>
            </div>
          </RadioGroup>
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
