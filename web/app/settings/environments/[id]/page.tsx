"use client"

import { useState, useEffect } from "react"
import { useParams, useRouter } from "next/navigation"
import { Eye, EyeOff, Check, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { EnvironmentFormValues, environmentSchema } from "../columns"
import { fetchEnvironment, updateEnvironment, validateKubernetes, validateImageRepository, createNamespace } from "@/lib/api/environments"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
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

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"

export default function EnvironmentEditPage() {
  const params = useParams()
  const router = useRouter()
  const id = params.id as string
  const [isLoading, setIsLoading] = useState(true)
  const [showToken, setShowToken] = useState(false)
  const [showRepoPassword, setShowRepoPassword] = useState(false)
  
  const [isK8sValidated, setIsK8sValidated] = useState(false)
  const [isRepoValidated, setIsRepoValidated] = useState(false)
  const [isValidatingK8s, setIsValidatingK8s] = useState(false)
  const [isValidatingRepo, setIsValidatingRepo] = useState(false)
  
  const [showCreateNamespaceDialog, setShowCreateNamespaceDialog] = useState(false)
  const [missingNamespace, setMissingNamespace] = useState("")

  const form = useForm<EnvironmentFormValues>({
    resolver: zodResolver(environmentSchema),
    defaultValues: {
      name: "",
      kubernetesApiServer: {
        url: "",
        token: "",
      },
      workNamespace: "",
      imageRepository: {
        url: "",
        username: "",
        password: "",
      },
      buildStorageClass: "",
    },
  })

  // Watch for changes to invalidate validation status
  useEffect(() => {
    const subscription = form.watch((value, { name, type }) => {
      if (name?.startsWith("kubernetesApiServer") || name === "workNamespace") {
        setIsK8sValidated(false)
      }
      if (name?.startsWith("imageRepository")) {
        setIsRepoValidated(false)
      }
    })
    return () => subscription.unsubscribe()
  }, [form.watch])

  useEffect(() => {
    const loadEnvironment = async () => {
      try {
        setIsLoading(true)
        const response = await fetchEnvironment(id)
        if (response.success && response.data) {
          const env = response.data
          form.reset({
            id: env.id,
            name: env.name,
            workNamespace: env.workNamespace,
            buildStorageClass: env.buildStorageClass || "",
            kubernetesApiServer: {
              url: env.kubernetesApiServer?.url || "",
              token: env.kubernetesApiServer?.token || "",
            },
            imageRepository: {
              url: env.imageRepository?.url || "",
              username: env.imageRepository?.username || "",
              password: env.imageRepository?.password || "",
            },
          })
        } else {
          toast.error("加载环境失败")
          router.push("/settings/environments")
        }
      } catch (error) {
        toast.error("加载环境失败")
        console.error(error)
        router.push("/settings/environments")
      } finally {
        setIsLoading(false)
      }
    }

    if (id) {
      loadEnvironment()
    }
  }, [id, form, router])

  const handleCreateNamespace = async () => {
    try {
        setShowCreateNamespaceDialog(false)
        const k8sConfig = form.getValues("kubernetesApiServer")
        const loadingToast = toast.loading("正在创建工作空间...")
        const res = await createNamespace({
            kubernetesApiServer: k8sConfig!,
            workNamespace: missingNamespace
        })
        toast.dismiss(loadingToast)
        if (res.success) {
            toast.success("工作空间创建成功")
            // Re-validate immediately
            handleValidateK8s()
        } else {
            toast.error("工作空间创建失败")
        }
    } catch (e) {
        toast.dismiss()
        toast.error("工作空间创建失败")
    }
  }

  const handleValidateK8s = async () => {
    setIsValidatingK8s(true)
    try {
      const k8sConfig = form.getValues("kubernetesApiServer")
      const workNamespace = form.getValues("workNamespace")
      
      if (!workNamespace) {
        toast.error("请先填写工作命名空间")
        setIsValidatingK8s(false)
        return
      }

      const res = await validateKubernetes({
        kubernetesApiServer: k8sConfig!,
        workNamespace: workNamespace
      })

      if (res.success && res.data?.success) {
        setIsK8sValidated(true)
        toast.success("Kubernetes 配置验证通过")
      } else if (res.data?.status === "NAMESPACE_MISSING") {
        setIsK8sValidated(false)
        setMissingNamespace(workNamespace)
        setShowCreateNamespaceDialog(true)
      } else {
        setIsK8sValidated(false)
        toast.error(res.data?.message || "Kubernetes 配置验证失败")
      }
    } catch (e) {
      setIsK8sValidated(false)
      toast.error("验证过程出错")
    } finally {
      setIsValidatingK8s(false)
    }
  }

  const handleValidateRepo = async () => {
    setIsValidatingRepo(true)
    try {
      const repoConfig = form.getValues("imageRepository")
      const res = await validateImageRepository(repoConfig!)
      if (res.success && res.data) {
        setIsRepoValidated(true)
        toast.success("镜像仓库配置验证通过")
      } else {
        setIsRepoValidated(false)
        toast.error("镜像仓库配置验证失败")
      }
    } catch (e) {
      setIsRepoValidated(false)
      toast.error("验证过程出错")
    } finally {
      setIsValidatingRepo(false)
    }
  }

  const onSubmit = async (data: EnvironmentFormValues) => {
    try {
      const res = await updateEnvironment(id, data)
      if (res.success) {
        toast.success("环境更新成功")
        router.push("/settings/environments")
      } else {
        toast.error("环境更新失败")
      }
    } catch (e) {
      console.error(e)
      toast.error("环境更新失败")
    }
  }

  if (isLoading) {
    return <div className="flex justify-center items-center h-full"><Loader2 className="animate-spin h-8 w-8" /></div>
  }

  return (
    <div className="space-y-6">
      <div className="flex gap-6">
        <div className="flex-1 min-w-0">
              <Form {...form}>
                <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
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
                    <div className="flex items-center justify-between">
                        <h4 className="text-sm font-medium leading-none text-muted-foreground">Kubernetes 配置</h4>
                        <Button
                          type="button"
                          variant={isK8sValidated ? "outline" : "secondary"}
                          size="sm"
                          onClick={handleValidateK8s}
                          disabled={isValidatingK8s || isK8sValidated}
                          className={isK8sValidated ? "text-green-600 border-green-600 hover:text-green-600" : ""}
                        >
                          {isValidatingK8s && <Loader2 className="mr-2 h-3 w-3 animate-spin" />}
                          {isK8sValidated ? (
                            <>
                              <Check className="mr-2 h-3 w-3" />
                              验证通过
                            </>
                          ) : "验证连接"}
                        </Button>
                    </div>
                    <div className="grid grid-cols-2 gap-4">
                      <FormField
                        control={form.control}
                        name="kubernetesApiServer.url"
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
                        name="kubernetesApiServer.token"
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
                    <div className="flex items-center justify-between">
                        <h4 className="text-sm font-medium leading-none text-muted-foreground">镜像仓库配置</h4>
                        <Button
                          type="button"
                          variant={isRepoValidated ? "outline" : "secondary"}
                          size="sm"
                          onClick={handleValidateRepo}
                          disabled={isValidatingRepo || isRepoValidated}
                          className={isRepoValidated ? "text-green-600 border-green-600 hover:text-green-600" : ""}
                        >
                          {isValidatingRepo && <Loader2 className="mr-2 h-3 w-3 animate-spin" />}
                          {isRepoValidated ? (
                            <>
                              <Check className="mr-2 h-3 w-3" />
                              验证通过
                            </>
                          ) : "验证连接"}
                        </Button>
                    </div>
                    <div className="grid grid-cols-2 gap-4">
                      <FormField
                        control={form.control}
                        name="imageRepository.url"
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
                        name="imageRepository.username"
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
                        name="imageRepository.password"
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
                                </Button>
                              </div>
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                        )}
                      />
                    </div>
                  </div>

                  <div className="flex justify-end gap-2">
                    <Button type="submit" disabled={!isK8sValidated || !isRepoValidated}>保存配置</Button>
                  </div>
                </form>
              </Form>
        </div>
      </div>
      
      <AlertDialog open={showCreateNamespaceDialog} onOpenChange={setShowCreateNamespaceDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>工作空间不存在</AlertDialogTitle>
            <AlertDialogDescription>
              检测到工作空间 <strong>{missingNamespace}</strong> 在集群中不存在，是否立即创建？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleCreateNamespace}>创建并继续</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
