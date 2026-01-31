"use client"

import { useState, useEffect } from "react"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Skeleton } from "@/components/ui/skeleton"
import { ApplicationEnvironment } from "@/lib/api/types"
import { getApplicationEnvironments } from "@/lib/api/applications"
import { toast } from "sonner"

interface ApplicationEnvironmentSelectorProps {
  namespace?: string
  applicationName?: string
  value?: string
  onValueChange?: (value: string) => void
  onEnvironmentsLoaded?: (envs: ApplicationEnvironment[]) => void
  children?: React.ReactNode
  className?: string
}

export function ApplicationEnvironmentSelector({
  namespace,
  applicationName,
  value,
  onValueChange,
  onEnvironmentsLoaded,
  children,
  className,
}: ApplicationEnvironmentSelectorProps) {
  const [environments, setEnvironments] = useState<ApplicationEnvironment[]>([])
  const [isLoading, setIsLoading] = useState(false)

  useEffect(() => {
    const loadEnvironments = async () => {
      if (!namespace || !applicationName) return

      setIsLoading(true)
      try {
        const res = await getApplicationEnvironments(namespace, applicationName)
        if (res.data) {
          setEnvironments(res.data)
          onEnvironmentsLoaded?.(res.data)
        }
      } catch (error) {
        console.error(error)
        toast.error("Failed to fetch environments")
      } finally {
        setIsLoading(false)
      }
    }
    loadEnvironments()
  }, [namespace, applicationName]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <Tabs value={value} onValueChange={onValueChange} className={className}>
      <div className="flex items-center space-x-2 mb-2">
        <span className="text-sm text-muted-foreground whitespace-nowrap">环境</span>
        <TabsList className="justify-start h-auto flex-wrap">
          {environments.map((env) => (
            <TabsTrigger
              key={env.environmentName}
              value={env.environmentName}
              className="px-6"
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
        <div className="py-8 text-center text-muted-foreground text-sm border rounded-md mt-2 border-dashed mb-4">
          暂无环境配置，请先在基本信息中配置部署环境
        </div>
      )}

      {children}
    </Tabs>
  )
}
