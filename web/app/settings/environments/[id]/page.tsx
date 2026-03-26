"use client"

import { useState, useEffect, type CSSProperties } from "react"
import { useParams, useRouter } from "next/navigation"
import { Eye, EyeOff, Check, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { EnvironmentFormValues, getEnvironmentSchema } from "../columns"
import { fetchEnvironment, updateEnvironment, validateKubernetes, validateImageRepository, createNamespace, deleteEnvironment } from "@/lib/api/environments"
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
import { useLanguage } from "@/contexts/language-context"

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
  const { t } = useLanguage()
  const [isLoading, setIsLoading] = useState(true)
  const [showToken, setShowToken] = useState(false)
  const [showRepoPassword, setShowRepoPassword] = useState(false)
  
  const [isK8sValidated, setIsK8sValidated] = useState(false)
  const [isRepoValidated, setIsRepoValidated] = useState(false)
  const [isValidatingK8s, setIsValidatingK8s] = useState(false)
  const [isValidatingRepo, setIsValidatingRepo] = useState(false)
  
  const [showCreateNamespaceDialog, setShowCreateNamespaceDialog] = useState(false)
  const [missingNamespace, setMissingNamespace] = useState("")

  const [environmentName, setEnvironmentName] = useState("")
  const [showDeleteDialog, setShowDeleteDialog] = useState(false)
  const [deleteConfirmInput, setDeleteConfirmInput] = useState("")
  const [isDeleting, setIsDeleting] = useState(false)

  const form = useForm<EnvironmentFormValues>({
    resolver: zodResolver(getEnvironmentSchema(t)),
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
    const subscription = form.watch((_, { name }) => {
      if (name?.startsWith("kubernetesApiServer") || name === "workNamespace") {
        setIsK8sValidated(false)
      }
      if (name?.startsWith("imageRepository")) {
        setIsRepoValidated(false)
      }
    })
    return () => subscription.unsubscribe()
  }, [form])

  useEffect(() => {
    const loadEnvironment = async () => {
      try {
        setIsLoading(true)
        const response = await fetchEnvironment(id)
        if (response.success && response.data) {
          const env = response.data
          setEnvironmentName(env.name)
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
          toast.error(t("env.loadError"))
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
        const loadingToast = toast.loading(t("env.workspaceCreating"))
        const res = await createNamespace({
            kubernetesApiServer: k8sConfig!,
            workNamespace: missingNamespace
        })
        toast.dismiss(loadingToast)
        if (res.success) {
            toast.success(t("env.workspaceCreated"))
            // Re-validate immediately
            handleValidateK8s()
        } else {
            toast.error(t("env.workspaceCreateError"))
        }
    } catch {
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
        toast.error(t("env.workNsRequired"))
        setIsValidatingK8s(false)
        return
      }

      const res = await validateKubernetes({
        kubernetesApiServer: k8sConfig!,
        workNamespace: workNamespace
      })

      if (res.success && res.data?.success) {
        setIsK8sValidated(true)
        toast.success(t("env.k8sValidated"))
      } else if (res.data?.status === "NAMESPACE_MISSING") {
        setIsK8sValidated(false)
        setMissingNamespace(workNamespace)
        setShowCreateNamespaceDialog(true)
      } else {
        setIsK8sValidated(false)
        toast.error(res.data?.message || t("env.k8sValidateFailed"))
      }
    } catch {
      setIsK8sValidated(false)
      toast.error(t("env.validateError"))
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
        toast.success(t("env.repoValidated"))
      } else {
        setIsRepoValidated(false)
        toast.error(t("env.repoValidateFailed"))
      }
    } catch {
      setIsRepoValidated(false)
      toast.error(t("env.validateError"))
    } finally {
      setIsValidatingRepo(false)
    }
  }

  const onSubmit = async (data: EnvironmentFormValues) => {
    try {
      const res = await updateEnvironment(id, data)
      if (res.success) {
        toast.success(t("env.updateSuccess"))
        router.push("/settings/environments")
      } else {
        toast.error(t("env.updateError"))
      }
    } catch (e) {
      console.error(e)
      toast.error("环境更新失败")
    }
  }

  const handleDelete = async () => {
    if (!environmentName || deleteConfirmInput !== environmentName) return
    setIsDeleting(true)
    let ok = false
    try {
      const res = await deleteEnvironment(id)
      if (res.success) {
        ok = true
        toast.success(t("env.deleteSuccess"))
        router.push("/settings/environments")
      } else {
        toast.error(t("env.deleteError"))
      }
    } catch (e) {
      console.error(e)
      toast.error("环境删除失败")
    } finally {
      setIsDeleting(false)
      if (ok) {
        setShowDeleteDialog(false)
        setDeleteConfirmInput("")
      }
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
                    <h4 className="text-sm font-medium leading-none text-muted-foreground">{t("env.basicInfo")}</h4>
                    <FormField
                      control={form.control}
                      name="name"
                      render={({ field }) => (
                        <FormItem>
                          <FormLabel>{t("env.col.name")}</FormLabel>
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
                        <h4 className="text-sm font-medium leading-none text-muted-foreground">{t("env.k8sConfig")}</h4>
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
                              {t("env.validated")}
                            </>
                          ) : t("env.validate")}
                        </Button>
                    </div>
                    <div className="grid grid-cols-2 gap-4">
                      <FormField
                        control={form.control}
                        name="kubernetesApiServer.url"
                        render={({ field }) => (
                          <FormItem className="col-span-2">
                            <FormLabel>{t("env.apiServerUrl")}</FormLabel>
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
                            <FormLabel>{t("env.apiToken")}</FormLabel>
                            <FormControl>
                              <div className="relative w-full">
                                <Textarea 
                                  placeholder="sk-..." 
                                  {...field} 
                                  rows={3}
                                  className="pr-10 min-h-[unset] max-h-[80px] break-all resize-none overflow-y-auto"
                                  style={{
                                    WebkitTextSecurity: showToken ? 'none' : 'disc',
                                  } as CSSProperties}
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
                            <FormLabel>{t("env.workNamespace")}</FormLabel>
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
                            <FormLabel>{t("env.buildStorageClass")}</FormLabel>
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
                        <h4 className="text-sm font-medium leading-none text-muted-foreground">{t("env.imageRepoConfig")}</h4>
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
                              {t("env.validated")}
                            </>
                          ) : t("env.validate")}
                        </Button>
                    </div>
                    <div className="grid grid-cols-2 gap-4">
                      <FormField
                        control={form.control}
                        name="imageRepository.url"
                        render={({ field }) => (
                          <FormItem className="col-span-2">
                            <FormLabel>{t("env.repoUrl")}</FormLabel>
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
                            <FormLabel>{t("env.repoUsername")}</FormLabel>
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
                            <FormLabel>{t("env.repoPassword")}</FormLabel>
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

                  <div className="flex justify-between gap-2">
                    <Button
                      type="button"
                      variant="destructive"
                      onClick={() => {
                        setDeleteConfirmInput("")
                        setShowDeleteDialog(true)
                      }}
                    >
                      {t("env.delete")}
                    </Button>
                    <Button type="submit" disabled={!isK8sValidated || !isRepoValidated}>{t("env.save")}</Button>
                  </div>
                </form>
              </Form>
        </div>
      </div>
      
      <AlertDialog open={showCreateNamespaceDialog} onOpenChange={setShowCreateNamespaceDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("env.nsNotExistTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("env.nsNotExistDescPrefix")}<strong>{missingNamespace}</strong>{t("env.nsNotExistDescSuffix")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction onClick={handleCreateNamespace}>{t("env.nsCreate")}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={showDeleteDialog} onOpenChange={setShowDeleteDialog}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("env.deleteTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("env.deleteDescPrefix")}<strong>{environmentName}</strong>{t("env.deleteDescSuffix")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="space-y-2">
            <Input
              value={deleteConfirmInput}
              onChange={(e) => setDeleteConfirmInput(e.target.value)}
              placeholder={environmentName || t("env.namePlaceholder")}
              autoFocus
            />
          </div>
          <AlertDialogFooter>
            <AlertDialogCancel
              disabled={isDeleting}
              onClick={() => setDeleteConfirmInput("")}
            >
              {t("common.cancel")}
            </AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              disabled={isDeleting || !environmentName || deleteConfirmInput !== environmentName}
              onClick={(e) => {
                e.preventDefault()
                handleDelete()
              }}
            >
              {isDeleting && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              {t("env.deleteConfirmBtn")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
