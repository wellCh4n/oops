"use client"

import { useState, useEffect } from "react"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Skeleton } from "@/components/ui/skeleton"
import { ApplicationEnvironment } from "@/lib/api/types"
import { getApplicationEnvironments } from "@/lib/api/applications"
import { toast } from "sonner"
import { Server } from "lucide-react"
import { useLanguage } from "@/contexts/language-context"

interface ApplicationEnvironmentSelectorProps {
  namespace?: string
  applicationName?: string
  value?: string
  onValueChange?: (value: string) => void
  onEnvironmentsLoaded?: (envs: ApplicationEnvironment[]) => void
  onLoadingChange?: (loading: boolean) => void
  children?: React.ReactNode
  className?: string
}

export function ApplicationEnvironmentSelector({
  namespace,
  applicationName,
  value,
  onValueChange,
  onEnvironmentsLoaded,
  onLoadingChange,
  children,
  className,
}: ApplicationEnvironmentSelectorProps) {
  const [environments, setEnvironments] = useState<ApplicationEnvironment[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const { t } = useLanguage()

  useEffect(() => {
    const loadEnvironments = async () => {
      if (!namespace || !applicationName) {
        onLoadingChange?.(false)
        return
      }

      setIsLoading(true)
      onLoadingChange?.(true)
      try {
        const res = await getApplicationEnvironments(namespace, applicationName)
        if (res.data) {
          setEnvironments(res.data)
          onEnvironmentsLoaded?.(res.data)
        }
      } catch (error) {
        console.error(error)
        toast.error(t("apps.envSelector.fetchError"))
      } finally {
        setIsLoading(false)
        onLoadingChange?.(false)
      }
    }
    loadEnvironments()
  }, [namespace, applicationName]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <Tabs value={value} onValueChange={onValueChange} className={className}>
      <div className="flex items-center gap-2">
        <span className="flex items-center gap-1 text-sm text-muted-foreground whitespace-nowrap"><Server className="h-3.5 w-3.5" />{t("apps.envSelector.label")}</span>
        <TabsList className="justify-start h-auto flex-wrap">
          {environments.map((env) => (
            <TabsTrigger
              key={env.environmentName}
              value={env.environmentName}
              className="px-6 cursor-pointer"
            >
              {isLoading ? (
                <Skeleton className="h-4 w-16 bg-muted-foreground/20" />
              ) : (
                env.environmentName
              )}
            </TabsTrigger>
          ))}
        </TabsList>
      </div>

      {environments.length === 0 && !isLoading && (
        <div className="py-8 text-center text-muted-foreground text-sm border rounded-md border-dashed">
          {t("apps.envSelector.noEnv")}
        </div>
      )}

      {children}
    </Tabs>
  )
}
