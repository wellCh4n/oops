"use client"

import { useForm, useFieldArray } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { Button } from "@/components/ui/button"
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import Editor from "@monaco-editor/react"
import { ApplicationFormValues, applicationSchema } from "./schema"
import { Application, Environment } from "@/lib/api/types"
import { fetchEnvironments } from "@/lib/api/environments"
import { Plus, X } from "lucide-react"
import { useEffect, useState } from "react"

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

import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { useRouter, usePathname, useSearchParams } from "next/navigation"

interface ApplicationFormProps {
  initialData?: Application
  initialEnvConfigs?: { environmentId: string; buildCommand?: string; replicas?: number }[]
  onSaveAppInfo: (data: ApplicationFormValues) => void
  onSaveEnvConfigs?: (data: ApplicationFormValues) => void
  onCancel: () => void
  submitLabel?: string
  namespaceSelect?: React.ReactNode
  showEnvConfig?: boolean
}

export function ApplicationForm({ initialData, initialEnvConfigs, onSaveAppInfo, onSaveEnvConfigs, onCancel, submitLabel = "Save", namespaceSelect, showEnvConfig = true }: ApplicationFormProps) {
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const currentTab = searchParams.get("tab") || "app-info"

  const [environments, setEnvironments] = useState<Environment[]>([])
  const [deleteConfirm, setDeleteConfirm] = useState<{ isOpen: boolean; index: number | null }>({
    isOpen: false,
    index: null,
  })

  const form = useForm<ApplicationFormValues>({
    resolver: zodResolver(applicationSchema),
    defaultValues: initialData ? {
      id: initialData.id,
      name: initialData.name,
      description: initialData.description,
      repository: initialData.repository,
      dockerFile: initialData.dockerFile,
      buildImage: initialData.buildImage,
      environmentConfigs: initialEnvConfigs ?? [],
    } : {
      name: "",
      description: "",
      repository: "",
      dockerFile: "Dockerfile",
      buildImage: "",
      environmentConfigs: [],
    },
    mode: "onChange",
  })

  const { fields, append, remove } = useFieldArray({
    control: form.control,
    name: "environmentConfigs",
  })

  useEffect(() => {
    fetchEnvironments().then(res => {
      if (res.data) setEnvironments(res.data)
    }).catch(console.error)
  }, [])

  const getEnvName = (id: string) => environments.find(e => e.id === id)?.name || id
  const availableEnvs = environments.filter(e => !fields.some(f => f.environmentId === e.id))

  const handleSaveAppInfoClick = async (e: React.MouseEvent) => {
    e.preventDefault()
    const isValid = await form.trigger(["name", "description", "repository", "dockerFile", "buildImage"])
    if (isValid) {
      onSaveAppInfo(form.getValues())
    }
  }

  const handleSaveEnvConfigsClick = async (e: React.MouseEvent) => {
    e.preventDefault()
    const isValid = await form.trigger("environmentConfigs")
    if (isValid && onSaveEnvConfigs) {
      onSaveEnvConfigs(form.getValues())
    }
  }

  // Set default tab to the first environment if available, otherwise undefined
  // Note: Tabs defaultValue is uncontrolled, so changing it dynamically is tricky.
  // We can use 'value' prop for controlled component or just let user click.
  // If we add a new tab, we might want to switch to it.
  
  const [activeTab, setActiveTab] = useState<string | undefined>(undefined)
  
  useEffect(() => {
    if (fields.length > 0 && !activeTab) {
      setActiveTab(fields[0].environmentId)
    }
  }, [fields, activeTab])

  const handleTabChange = (value: string) => {
    const params = new URLSearchParams(searchParams.toString())
    params.set("tab", value)
    router.push(`${pathname}?${params.toString()}`)
  }

  return (
    <Form {...form}>
      <form className="space-y-6 w-full" autoComplete="off">
        <Tabs value={currentTab} onValueChange={handleTabChange} className="w-full">
          <div className="flex items-center justify-between">
            <TabsList>
              <TabsTrigger value="app-info" className="px-6">应用信息</TabsTrigger>
              {showEnvConfig && <TabsTrigger value="build-env" className="px-6">环境与配置</TabsTrigger>}
            </TabsList>
          </div>

          <TabsContent value="app-info">
            <div className="space-y-4 py-4 w-full md:w-1/2">
              {namespaceSelect && (
                <div className="space-y-2">
                  {namespaceSelect}
                </div>
              )}
              <FormField
                control={form.control}
                name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>名称</FormLabel>
                    <FormControl>
                      <Input {...field} disabled={!!initialData} autoComplete="off" />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="description"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>描述</FormLabel>
                    <FormControl>
                      <Textarea placeholder="Application description..." {...field} autoComplete="off" />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="repository"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>代码仓库</FormLabel>
                    <FormControl>
                      <Input {...field} autoComplete="off" />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="dockerFile"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Dockerfile 路径</FormLabel>
                    <FormControl>
                      <Input placeholder="Dockerfile" {...field} autoComplete="off" />
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
                      <Input {...field} autoComplete="off" />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <div className="flex space-x-2 pt-4">
                 <Button type="button" variant="outline" onClick={onCancel}>
                   取消
                 </Button>
                 <Button type="button" onClick={handleSaveAppInfoClick}>
                   {submitLabel}
                 </Button>
              </div>
            </div>
          </TabsContent>

          {showEnvConfig && (
          <TabsContent value="build-env">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4 py-4">
              <div>
                <Tabs value={activeTab} onValueChange={setActiveTab} className="w-full">
                  <div className="flex items-center space-x-2">
                    <span className="text-sm text-muted-foreground whitespace-nowrap">环境</span>
                    <TabsList className="justify-start h-auto flex-wrap">
                      {fields.map((field, index) => (
                        <TabsTrigger 
                          key={field.id} 
                          value={field.environmentId}
                          className="group relative overflow-visible px-6"
                        >
                          {getEnvName(field.environmentId)}
                          {activeTab === field.environmentId && (
                            <div
                              className="absolute -top-1.5 -right-1.5 opacity-0 group-hover:opacity-100 flex items-center justify-center h-4 w-4 rounded-full bg-muted-foreground/50 hover:bg-muted-foreground text-white cursor-pointer shadow-sm transition-all duration-200 group-hover:delay-300 z-10"
                              onClick={(e) => {
                                e.stopPropagation()
                                e.preventDefault()
                                setDeleteConfirm({ isOpen: true, index: index })
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
                              append({ environmentId: env.id, buildCommand: "", replicas: 0 })
                              setActiveTab(env.id)
                            }}
                          >
                            {env.name}
                          </DropdownMenuItem>
                        ))}
                        {availableEnvs.length === 0 && (
                          <DropdownMenuItem disabled>
                            {environments.length === 0 ? "Loading..." : "No more environments"}
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
                    <TabsContent key={field.id} value={field.environmentId} className="space-y-4 pt-4">
                      <FormField
                        control={form.control}
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
                      <FormField
                        control={form.control}
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
                    </TabsContent>
                  ))}
                </Tabs>
                
                {onSaveEnvConfigs && (
                  <div className="flex space-x-2 mt-4">
                    <Button type="button" onClick={handleSaveEnvConfigsClick}>
                      保存环境配置
                    </Button>
                  </div>
                )}
              </div>
            </div>
          </TabsContent>
          )}
        </Tabs>
        
        <Dialog open={deleteConfirm.isOpen} onOpenChange={(open) => setDeleteConfirm(prev => ({ ...prev, isOpen: open }))}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>确认删除环境配置？</DialogTitle>
              <DialogDescription>
                删除后该环境的配置将丢失，此操作不可撤销。
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button variant="outline" onClick={() => setDeleteConfirm({ isOpen: false, index: null })}>取消</Button>
              <Button 
                variant="destructive" 
                onClick={() => {
                  if (deleteConfirm.index !== null) {
                    const index = deleteConfirm.index
                    const fieldToRemove = fields[index];
                    if (activeTab === fieldToRemove.environmentId) {
                       const newActiveId = fields[index - 1]?.environmentId || fields[index + 1]?.environmentId;
                       setActiveTab(newActiveId);
                    }
                    remove(index)
                    setDeleteConfirm({ isOpen: false, index: null })
                  }
                }}
              >
                删除
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </form>
    </Form>
  )
}
