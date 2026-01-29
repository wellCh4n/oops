"use client"

import { useState, useEffect } from "react"
import { Plus, Eye, EyeOff, Check, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { DataTable } from "@/components/ui/data-table"
import { columns, EnvironmentFormValues, environmentSchema } from "./columns"
import { Environment } from "@/lib/api/types"
import { fetchEnvironments, createEnvironmentStream } from "@/lib/api/environments"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
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
import { Separator } from "@/components/ui/separator"

export default function EnvironmentsPage() {
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [viewingEnv, setViewingEnv] = useState<Environment | null>(null)
  const [showToken, setShowToken] = useState(false)
  const [showRepoPassword, setShowRepoPassword] = useState(false)
  const [isCreating, setIsCreating] = useState(false)
  const [creationSteps, setCreationSteps] = useState<{ step: number; status: string; message: string }[]>([])

  const form = useForm<EnvironmentFormValues>({
    resolver: zodResolver(environmentSchema),
    defaultValues: {
      name: "",
      apiServerUrl: "",
      apiServerToken: "",
      workNamespace: "",
      imageRepositoryUrl: "",
      imageRepositoryUsername: "",
      imageRepositoryPassword: "",
      buildStorageClass: "",
    },
  })

  const loadEnvironments = async () => {
    try {
      setIsLoading(true)
      const response = await fetchEnvironments()
      console.log("Fetched environments:", response)
      if (response.success) {
        setEnvironments(response.data)
      } else {
        toast.error(response.message || "获取环境列表失败")
      }
    } catch (error) {
      toast.error("获取环境列表失败")
      console.error(error)
    } finally {
      setIsLoading(false)
    }
  }

  useEffect(() => {
    loadEnvironments()
  }, [])

  useEffect(() => {
    if (viewingEnv) {
      form.reset({
        ...viewingEnv,
        id: viewingEnv.id,
        imageRepositoryUsername: viewingEnv.imageRepositoryUsername || "",
        imageRepositoryPassword: viewingEnv.imageRepositoryPassword || "",
      })
    } else {
      form.reset({
        name: "",
        apiServerUrl: "",
        apiServerToken: "",
        workNamespace: "",
        imageRepositoryUrl: "",
        imageRepositoryUsername: "",
        imageRepositoryPassword: "",
        buildStorageClass: "",
      })
    }
  }, [viewingEnv, form, dialogOpen])

  const handleCreate = async (data: EnvironmentFormValues) => {
    setIsCreating(true)
    setCreationSteps([])

    await createEnvironmentStream(
      data,
      (step, status, message) => {
        setCreationSteps((prev) => {
          const existing = prev.find((s) => s.step === step)
          if (existing) {
            return prev.map((s) => {
              if (s.step === step) {
                return { ...s, status, message }
              }
              return s
            })
          }
          return [...prev, { step, status, message }]
        })
      },
      () => {
        toast.success("环境创建成功")
        setIsCreating(false)
        setDialogOpen(false)
        loadEnvironments()
      },
      (error) => {
        toast.error("创建环境失败")
        console.error(error)
      }
    )
  }

  const onSubmit = async (data: EnvironmentFormValues) => {
    await handleCreate(data)
  }

  const openCreateDialog = () => {
    setViewingEnv(null)
    setCreationSteps([])
    setDialogOpen(true)
  }

  const openViewDialog = (env: Environment) => {
    setViewingEnv(env)
    setDialogOpen(true)
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <Input
          placeholder="搜索环境..."
          className="max-w-sm"
        />
        <Button onClick={openCreateDialog}>
          <Plus className="mr-2 h-4 w-4" />
          创建环境
        </Button>
      </div>

      <DataTable 
        columns={columns} 
        data={environments} 
        meta={{
          onView: openViewDialog,
        }}
      />

      <Dialog open={dialogOpen} onOpenChange={(open) => {
        setDialogOpen(open)
        if (!open) {
          setViewingEnv(null)
          setShowToken(false)
          setShowRepoPassword(false)
          setIsCreating(false)
          setCreationSteps([])
        }
      }}>
        <DialogContent className={`${!viewingEnv ? "sm:max-w-[900px]" : "sm:max-w-[600px]"} max-h-[80vh] overflow-y-auto transition-all duration-300`}>
          <div className="flex gap-6">
            <div className="flex-1 min-w-0">
          <DialogHeader>
            <DialogTitle>
              {viewingEnv ? "环境详情" : "创建环境"}
            </DialogTitle>
            <DialogDescription>
              {viewingEnv
                ? "查看环境的详细配置信息。"
                : "在系统中添加一个新的环境。"}
            </DialogDescription>
          </DialogHeader>
          <Form {...form}>
            <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
              <fieldset disabled={!!viewingEnv} className="space-y-6 disabled:opacity-100">
              <div className="space-y-4">
                <h4 className="text-sm font-medium leading-none text-muted-foreground">基本信息</h4>
                <FormField
                  control={form.control}
                  name="name"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>名称</FormLabel>
                      <FormControl>
                        <Input placeholder="Production" {...field} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>

              <Separator />

              <div className="space-y-4">
                <div className="flex items-center gap-2">
                    <h4 className="text-sm font-medium leading-none text-muted-foreground">Kubernetes 配置</h4>
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <FormField
                    control={form.control}
                    name="apiServerUrl"
                    render={({ field }) => (
                      <FormItem className="col-span-2">
                        <FormLabel>API 服务地址</FormLabel>
                        <FormControl>
                          <Input placeholder="https://api.example.com" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="apiServerToken"
                    render={({ field }) => (
                      <FormItem className="col-span-2">
                        <FormLabel>API 服务令牌</FormLabel>
                        <FormControl>
                          <div className="relative w-full">
                            <Textarea 
                              placeholder="sk-..." 
                              {...field} 
                              rows={3}
                              className="pr-10 min-h-[unset] max-h-[80px] break-all resize-none overflow-y-auto"
                              style={{
                                WebkitTextSecurity: showToken ? 'none' : 'disc',
                              } as any}
                            />
                            <Button
                              type="button"
                              variant="ghost"
                              size="icon"
                              className="absolute right-0 top-0 h-9 w-9 text-muted-foreground hover:bg-transparent"
                              onClick={() => setShowToken(!showToken)}
                            >
                              {showToken ? (
                                <EyeOff className="h-4 w-4" />
                              ) : (
                                <Eye className="h-4 w-4" />
                              )}
                              <span className="sr-only">
                                {showToken ? "隐藏令牌" : "显示令牌"}
                              </span>
                            </Button>
                          </div>
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="workNamespace"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>工作命名空间</FormLabel>
                        <FormControl>
                          <Input placeholder="default" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="buildStorageClass"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>构建存储类</FormLabel>
                        <FormControl>
                          <Input placeholder="standard" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </div>
              </div>

              <Separator />

              <div className="space-y-4">
                <div className="flex items-center gap-2">
                    <h4 className="text-sm font-medium leading-none text-muted-foreground">镜像仓库配置</h4>
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <FormField
                    control={form.control}
                    name="imageRepositoryUrl"
                    render={({ field }) => (
                      <FormItem className="col-span-2">
                        <FormLabel>仓库地址</FormLabel>
                        <FormControl>
                          <Input placeholder="docker.io/my-repo" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="imageRepositoryUsername"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>用户名</FormLabel>
                        <FormControl>
                          <Input placeholder="username" {...field} />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                  <FormField
                    control={form.control}
                    name="imageRepositoryPassword"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>密码</FormLabel>
                        <FormControl>
                          <div className="relative">
                            <Input 
                              type={showRepoPassword ? "text" : "password"} 
                              placeholder="password" 
                              {...field} 
                              className="pr-10"
                            />
                            <Button
                              type="button"
                              variant="ghost"
                              size="icon"
                              className="absolute right-0 top-0 h-9 w-9 text-muted-foreground hover:bg-transparent"
                              onClick={() => setShowRepoPassword(!showRepoPassword)}
                            >
                              {showRepoPassword ? (
                                <EyeOff className="h-4 w-4" />
                              ) : (
                                <Eye className="h-4 w-4" />
                              )}
                              <span className="sr-only">
                                {showRepoPassword ? "隐藏密码" : "显示密码"}
                              </span>
                            </Button>
                          </div>
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                </div>
              </div>
              </fieldset>

              {!viewingEnv && (
                <DialogFooter>
                  <div className="flex justify-end gap-2 w-full">
                      <Button type="submit">保存</Button>
                  </div>
                </DialogFooter>
              )}
            </form>
          </Form>
            </div>
            
            {!viewingEnv && (
              <div className="w-[280px] border-l pl-6 pt-2 shrink-0">
                <h3 className="font-medium mb-4 text-sm">初始化进度</h3>
                <div className="space-y-4">
                  {creationSteps.map(step => (
                    <div key={step.step} className="flex items-start gap-3 text-sm">
                      <div className="mt-0.5 shrink-0">
                        {step.status === 'PENDING' && <div className="w-4 h-4 rounded-full border-2 border-muted" />}
                        {step.status === 'RUNNING' && <Loader2 className="w-4 h-4 animate-spin text-primary" />}
                        {step.status === 'SUCCESS' && <Check className="w-4 h-4 text-green-500 font-bold" />}
                        {step.status === 'SKIPPED' && <Check className="w-4 h-4 text-yellow-500 font-bold" />}
                        {step.status === 'FAILURE' && <div className="w-4 h-4 rounded-full bg-destructive flex items-center justify-center text-destructive-foreground text-[10px] font-bold">!</div>}
                      </div>
                      <div className="flex flex-col gap-0.5">
                        <span className={`text-sm ${
                          step.status === 'PENDING' ? 'text-muted-foreground' : 
                          step.status === 'FAILURE' ? 'text-destructive' : 
                          'text-foreground'
                        }`}>{step.message}</span>
                        {step.status === 'SKIPPED' && <span className="text-xs text-muted-foreground">已存在，跳过</span>}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}
