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
import { useForm, useFieldArray, useFormContext } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { ApplicationPerformanceEnvFormValues, applicationPerformanceEnvSchema } from "../schema"
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "@/components/ui/tabs"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Plus, X } from "lucide-react"
import { Environment, ApplicationPerformanceEnvironmentConfig } from "@/lib/api/types"
import { Skeleton } from "@/components/ui/skeleton"
import { updateApplicationPerformanceEnvConfigs } from "@/lib/api/applications"
import { fetchEnvironments } from "@/lib/api/environments"
import { toast } from "sonner"

interface ApplicationPerformanceInfoProps {
  initialEnvConfigs?: ApplicationPerformanceEnvironmentConfig[]
  applicationId?: string
  applicationName?: string
  namespace?: string
}

export function ApplicationPerformanceInfo({
  initialEnvConfigs = [],
  applicationId,
  applicationName,
  namespace,
}: ApplicationPerformanceInfoProps) {
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [isLoadingEnvs, setIsLoadingEnvs] = useState(true)

  useEffect(() => {
    const loadEnvironments = async () => {
      try {
        const res = await fetchEnvironments()
        if (res.data) setEnvironments(res.data)
      } catch (error) {
        console.error(error)
        toast.error("Failed to fetch environments")
      } finally {
        setIsLoadingEnvs(false)
      }
    }
    loadEnvironments()
  }, [])

  const form = useForm<ApplicationPerformanceEnvFormValues>({
    resolver: zodResolver(applicationPerformanceEnvSchema),
    defaultValues: {
      environmentConfigs: initialEnvConfigs as any,
    },
    mode: "onChange",
  })

  const { control } = form
  const { fields, append, remove } = useFieldArray({
    control,
    name: "environmentConfigs",
  })

  const [activeTab, setActiveTab] = useState<string | undefined>(undefined)

  // Initialize activeTab
  useEffect(() => {
    if (fields.length > 0 && !activeTab) {
      setActiveTab(fields[0].environmentName)
    }
  }, [fields, activeTab])

  const availableEnvs = environments.filter(e => !fields.some(f => f.environmentName === e.name))

  const handleDelete = (index: number, environmentName: string) => {
    if (activeTab === environmentName) {
       const newActiveId = fields[index - 1]?.environmentName || fields[index + 1]?.environmentName
       setActiveTab(newActiveId)
    }
    remove(index)
  }

  return (
    <Form {...form}>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 py-4">
        <div>
          <Tabs value={activeTab} onValueChange={setActiveTab} className="w-full">
            <div className="flex items-center space-x-2">
              <span className="text-sm text-muted-foreground whitespace-nowrap">环境</span>
              <TabsList className="justify-start h-auto flex-wrap">
                {fields.map((field, index) => (
                  <TabsTrigger 
                    key={field.id} 
                    value={field.environmentName}
                    className="group relative overflow-visible px-6"
                  >
                    {isLoadingEnvs ? <Skeleton className="h-4 w-16 bg-muted-foreground/20" /> : field.environmentName}
                    {activeTab === field.environmentName && (
                      <div
                        className="absolute -top-1.5 -right-1.5 opacity-0 group-hover:opacity-100 flex items-center justify-center h-4 w-4 rounded-full bg-muted-foreground/50 hover:bg-muted-foreground text-white cursor-pointer shadow-sm transition-all duration-200 group-hover:delay-300 z-10"
                        onClick={(e) => {
                          e.stopPropagation()
                          e.preventDefault()
                          handleDelete(index, field.environmentName)
                        }}
                      >
                         <X className="h-2.5 w-2.5" />
                      </div>
                    )}
                  </TabsTrigger>
                ))}
              </TabsList>
              
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button variant="outline" size="icon" className="h-8 w-8 shrink-0">
                    <Plus className="h-4 w-4" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  {availableEnvs.map(env => (
                    <DropdownMenuItem 
                      key={env.id} 
                      onClick={() => {
                        append({ 
                          environmentName: env.name, 
                          replicas: 0, 
                          cpuRequest: "0.1", 
                          cpuLimit: "1", 
                          memoryRequest: "128", 
                          memoryLimit: "512" 
                        })
                        setActiveTab(env.name)
                      }}
                    >
                      {env.name}
                    </DropdownMenuItem>
                  ))}
                  {availableEnvs.length === 0 && (
                    <DropdownMenuItem disabled>
                      {isLoadingEnvs ? "Loading..." : "No more environments"}
                    </DropdownMenuItem>
                  )}
                </DropdownMenuContent>
              </DropdownMenu>
            </div>

            {fields.length === 0 && (
               <div className="py-8 text-center text-muted-foreground text-sm border rounded-md mt-2 border-dashed">
                 点击右上角 + 号添加环境配置
               </div>
            )}

            {fields.map((field, index) => (
              <TabsContent key={field.id} value={field.environmentName}>
                <SingleEnvironmentConfig
                  index={index}
                  applicationId={applicationId}
                  applicationName={applicationName}
                  namespace={namespace}
                />
              </TabsContent>
            ))}
          </Tabs>
        </div>
      </div>
    </Form>
  )
}

interface SingleEnvironmentConfigProps {
  index: number
  applicationId?: string
  applicationName?: string
  namespace?: string
}

function SingleEnvironmentConfig({ index, applicationId, applicationName, namespace }: SingleEnvironmentConfigProps) {
  const { control, getValues } = useFormContext<ApplicationPerformanceEnvFormValues>()
  const [isSaving, setIsSaving] = useState(false)

  const handleSave = async (e: React.MouseEvent) => {
    e.preventDefault()
    
    if (!applicationId || !applicationName || !namespace) {
      toast.error("请先保存应用基本信息")
      return
    }

    const values = getValues()
    const configs = values.environmentConfigs.map(config => ({
        ...config,
        namespace,
        applicationName,
    })) as ApplicationPerformanceEnvironmentConfig[]
    
    setIsSaving(true)
    try {
      await updateApplicationPerformanceEnvConfigs(namespace, applicationName, configs)
      toast.success("环境配置保存成功")
    } catch (error) {
      console.error(error)
      toast.error("保存环境配置失败")
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <div className="space-y-4 pt-4">
      <FormField
        control={control}
        name={`environmentConfigs.${index}.replicas`}
        render={({ field }) => (
          <FormItem>
            <FormLabel>副本数</FormLabel>
            <FormControl>
              <Input 
                type="number" 
                {...field}
                value={field.value ?? ''}
                onChange={e => {
                  const val = e.target.valueAsNumber
                  field.onChange(isNaN(val) ? undefined : val)
                }}
                autoComplete="off"
              />
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />
      <div className="grid grid-cols-2 gap-4">
        <FormField
          control={control}
          name={`environmentConfigs.${index}.cpuRequest`}
          render={({ field }) => (
            <FormItem>
              <FormLabel>CPU 请求 (core)</FormLabel>
              <FormControl>
                <Input 
                  placeholder="例如 0.1"
                  {...field}
                  autoComplete="off"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name={`environmentConfigs.${index}.cpuLimit`}
          render={({ field }) => (
            <FormItem>
              <FormLabel>CPU 限制 (core)</FormLabel>
              <FormControl>
                <Input 
                  placeholder="例如 1"
                  {...field}
                  autoComplete="off"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
      </div>
      <div className="grid grid-cols-2 gap-4">
        <FormField
          control={control}
          name={`environmentConfigs.${index}.memoryRequest`}
          render={({ field }) => (
            <FormItem>
              <FormLabel>内存 请求 (Mi)</FormLabel>
              <FormControl>
                <Input 
                  placeholder="例如 128"
                  {...field}
                  autoComplete="off"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name={`environmentConfigs.${index}.memoryLimit`}
          render={({ field }) => (
            <FormItem>
              <FormLabel>内存 限制 (Mi)</FormLabel>
              <FormControl>
                <Input 
                  placeholder="例如 512"
                  {...field}
                  autoComplete="off"
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
      </div>
      <div className="flex space-x-2 mt-4">
        <Button type="button" onClick={handleSave} disabled={isSaving}>
          {isSaving ? "保存中..." : "保存"}
        </Button>
      </div>
    </div>
  )
}
