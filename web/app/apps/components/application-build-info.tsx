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
import Editor from "@monaco-editor/react"
import { ApplicationBuildFormValues, applicationBuildSchema } from "../schema"
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
import { Environment, ApplicationBuildEnvironmentConfig, ApplicationBuildConfig } from "@/lib/api/types"
import { Skeleton } from "@/components/ui/skeleton"
import { updateApplicationBuildEnvConfigs, updateApplicationBuildConfig } from "@/lib/api/applications"
import { fetchEnvironments } from "@/lib/api/environments"
import { toast } from "sonner"

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

  const form = useForm<ApplicationBuildFormValues>({
    resolver: zodResolver(applicationBuildSchema),
    defaultValues: {
      repository: initialBuildConfig?.repository || "",
      dockerFile: initialBuildConfig?.dockerFile || "Dockerfile",
      buildImage: initialBuildConfig?.buildImage || "",
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
  const [isSaving, setIsSaving] = useState(false)

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

  const handleSave = async (data: ApplicationBuildFormValues) => {
    if (!applicationId || !applicationName || !namespace) {
      toast.error("请先保存应用基本信息")
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
      const envConfigs = data.environmentConfigs.map(config => ({
          ...config,
          namespace,
          applicationName,
      })) as ApplicationBuildEnvironmentConfig[]
      await updateApplicationBuildEnvConfigs(namespace, applicationName, envConfigs)

      toast.success("构建配置保存成功")
    } catch (error) {
      console.error(error)
      toast.error("保存配置失败")
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(handleSave)}>
        <div className="space-y-6">
          {/* Global Build Config Section */}
          <div className="space-y-4">
            <h3 className="text-lg font-medium">源码配置</h3>
            <FormField
              control={form.control}
              name="repository"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>代码仓库</FormLabel>
                  <FormControl>
                    <Input placeholder="输入代码仓库地址" {...field} />
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
                    <FormLabel>Dockerfile 路径</FormLabel>
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
                    <FormLabel>构建镜像</FormLabel>
                    <FormControl>
                      <Input placeholder="输入构建镜像" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
            </div>
          </div>

          <div className="border-t pt-6">
            <h3 className="text-lg font-medium mb-4">环境构建配置</h3>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
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
                              append({ environmentName: env.name, buildCommand: "" })
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
                      />
                    </TabsContent>
                  ))}
                </Tabs>
              </div>
            </div>
          </div>

          <div className="flex justify-end pt-4">
             <Button type="submit" disabled={isSaving}>
                {isSaving ? "保存中..." : "保存配置"}
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

  return (
    <div className="space-y-4 pt-4">
      <FormField
        control={control}
        name={`environmentConfigs.${index}.buildCommand`}
        render={({ field }) => (
          <FormItem>
            <FormLabel>构建命令</FormLabel>
            <FormControl>
              <div className="border rounded-md overflow-hidden">
                <div className="bg-muted px-3 py-1 text-xs text-muted-foreground border-b flex items-center">
                  <span>shell</span>
                </div>
                <div className="h-[200px]">
                  <Editor
                    height="100%"
                    defaultLanguage="shell"
                    theme="vs-dark"
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
