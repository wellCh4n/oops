"use client"

import { Suspense, useState, useEffect, type CSSProperties } from "react"
import { useParams, usePathname, useRouter, useSearchParams } from "next/navigation"
import { Eye, EyeOff, Check, Loader2, Info, Server, Package, AlertTriangle, KeyRound } from "lucide-react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { EnvironmentFormValues, getEnvironmentSchema } from "../columns"
import { fetchEnvironment, updateEnvironmentCluster, updateEnvironmentCredentials, validateKubernetes, validateImageRepository, createNamespace, deleteEnvironment } from "@/lib/api/environments"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { useLanguage } from "@/contexts/language-context"
import { ContentPage } from "@/components/content-page"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"

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

type EnvironmentTab = "basic" | "credentials" | "danger-zone"
const VALID_TABS = new Set<EnvironmentTab>(["basic", "credentials", "danger-zone"])
const DEFAULT_TAB: EnvironmentTab = "basic"

export default function EnvironmentEditPage() {
  return (
    <Suspense fallback={null}>
      <EnvironmentEditPageContent />
    </Suspense>
  )
}

function EnvironmentEditPageContent() {
  const params = useParams()
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const rawTab = searchParams.get("tab")
  const currentTab = rawTab && VALID_TABS.has(rawTab as EnvironmentTab)
    ? rawTab as EnvironmentTab
    : DEFAULT_TAB
  const id = params.id as string
  const { t } = useLanguage()
  const [isLoading, setIsLoading] = useState(true)
  const [showToken, setShowToken] = useState(false)
  const [showRepoPassword, setShowRepoPassword] = useState(false)
  const [showGitPassword, setShowGitPassword] = useState(false)
  const [showGitPrivateKey, setShowGitPrivateKey] = useState(false)
  
  const [isK8sValidated, setIsK8sValidated] = useState(false)
  const [isRepoValidated, setIsRepoValidated] = useState(false)
  const [isValidatingK8s, setIsValidatingK8s] = useState(false)
  const [isValidatingRepo, setIsValidatingRepo] = useState(false)
  
  const [showCreateNamespaceDialog, setShowCreateNamespaceDialog] = useState(false)
  const [missingNamespace, setMissingNamespace] = useState("")

  const [environment, setEnvironmentName] = useState("")
  const [showDeleteDialog, setShowDeleteDialog] = useState(false)
  const [deleteConfirmInput, setDeleteConfirmInput] = useState("")
  const [isDeleting, setIsDeleting] = useState(false)
  const [isSavingCluster, setIsSavingCluster] = useState(false)
  const [isSavingCredentials, setIsSavingCredentials] = useState(false)

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
      gitCredential: {
        username: "",
        password: "",
        privateKey: "",
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
            gitCredential: {
              username: env.gitCredential?.username || "",
              password: env.gitCredential?.password || "",
              privateKey: env.gitCredential?.privateKey || "",
            },
          })
        } else {
          toast.error(t("env.loadError"))
          router.push("/settings/environments")
        }
      } catch (error) {
        toast.error(t("env.loadError"))
        console.error(error)
        router.push("/settings/environments")
      } finally {
        setIsLoading(false)
      }
    }

    if (id) {
      loadEnvironment()
    }
  }, [id, form, router, t])

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
        toast.error(t("env.workspaceCreateError"))
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

  const handleTabChange = (value: string | number | null) => {
    if (!value || !VALID_TABS.has(value as EnvironmentTab)) return
    const nextParams = new URLSearchParams(searchParams.toString())
    nextParams.set("tab", value as string)
    router.push(`${pathname}?${nextParams.toString()}`)
  }

  const handleSaveCluster = async () => {
    const valid = await form.trigger(["kubernetesApiServer", "workNamespace", "buildStorageClass"])
    if (!valid) return
    setIsSavingCluster(true)
    try {
      const { kubernetesApiServer, workNamespace, buildStorageClass } = form.getValues()
      const res = await updateEnvironmentCluster(id, { kubernetesApiServer, workNamespace, buildStorageClass })
      if (res.success) {
        toast.success(t("env.updateSuccess"))
      } else {
        toast.error(res.message || t("env.updateError"))
      }
    } catch (e) {
      console.error(e)
      toast.error(t("env.updateError"))
    } finally {
      setIsSavingCluster(false)
    }
  }

  const handleSaveCredentials = async () => {
    const valid = await form.trigger(["imageRepository", "gitCredential"])
    if (!valid) return
    setIsSavingCredentials(true)
    try {
      const { imageRepository, gitCredential } = form.getValues()
      const res = await updateEnvironmentCredentials(id, { imageRepository, gitCredential })
      if (res.success) {
        toast.success(t("env.updateSuccess"))
      } else {
        toast.error(res.message || t("env.updateError"))
      }
    } catch (e) {
      console.error(e)
      toast.error(t("env.updateError"))
    } finally {
      setIsSavingCredentials(false)
    }
  }

  const handleDelete = async () => {
    if (!environment || deleteConfirmInput !== environment) return
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
      toast.error(t("env.deleteError"))
    } finally {
      setIsDeleting(false)
      if (ok) {
        setShowDeleteDialog(false)
        setDeleteConfirmInput("")
      }
    }
  }

  if (isLoading) {
    return <div className="flex justify-center items-center h-full"><Loader2 className="animate-spin size-8" /></div>
  }

  return (
    <ContentPage title={environment}>
      <div className="space-y-6">
      <div className="flex gap-6">
        <div className="flex-1 min-w-0">
              <Form {...form}>
                <form onSubmit={(event) => event.preventDefault()} className="w-full">
                <Tabs value={currentTab} onValueChange={handleTabChange} className="w-full">
                  <TabsList>
                    <TabsTrigger value="basic" className="px-6 cursor-pointer">{t("env.tab.basic")}</TabsTrigger>
                    <TabsTrigger value="credentials" className="px-6 cursor-pointer">{t("env.tab.credentials")}</TabsTrigger>
                    <TabsTrigger value="danger-zone" className="px-6 cursor-pointer data-active:bg-destructive data-active:text-white dark:data-active:border-destructive">{t("env.dangerZone")}</TabsTrigger>
                  </TabsList>

                  <TabsContent value="basic" keepMounted className="flex flex-col gap-4">
                  {/* Basic Info Block */}
                  <div className="border rounded-lg overflow-hidden">
                    <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
                      <Info className="size-4 text-muted-foreground" />
                      <span className="text-sm font-semibold">{t("env.basicInfo")}</span>
                    </div>
                    <div className="flex flex-col gap-4 p-4">
                      <FormField
                        control={form.control}
                        name="name"
                        render={({ field }) => (
                          <FormItem>
                            <FormLabel>{t("env.col.name")}</FormLabel>
                            <FormControl>
                              <Input placeholder="Production" disabled {...field} />
                            </FormControl>
                            <FormDescription>{t("env.nameImmutableDesc")}</FormDescription>
                            <FormMessage />
                          </FormItem>
                        )}
                      />
                    </div>
                  </div>

                  {/* K8s Config Block */}
                  <div className="border rounded-lg overflow-hidden">
                    <div className="flex items-center justify-between px-4 py-3 bg-muted/50 border-b">
                      <div className="flex items-center gap-2">
                        <Server className="size-4 text-muted-foreground" />
                        <span className="text-sm font-semibold">{t("env.k8sConfig")}</span>
                      </div>
                      <Button
                        type="button"
                        variant={isK8sValidated ? "outline" : "secondary"}
                        size="sm"
                        onClick={handleValidateK8s}
                        disabled={isValidatingK8s || isK8sValidated}
                        className={isK8sValidated ? "text-success border-success hover:text-success" : ""}
                      >
                        {isValidatingK8s && <Loader2 className="size-3 animate-spin" />}
                        {isK8sValidated ? (
                          <>
                            <Check className="size-3" />
                            {t("env.validated")}
                          </>
                        ) : t("env.validate")}
                      </Button>
                    </div>
                    <div className="flex flex-col gap-4 p-4">
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
                                    placeholder="token"
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
                                    className="absolute right-0 top-0 size-9 text-muted-foreground hover:bg-transparent"
                                    onClick={() => setShowToken(!showToken)}
                                  >
                                    {showToken ? (
                                      <EyeOff className="size-4" />
                                    ) : (
                                      <Eye className="size-4" />
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
                  </div>

                  <div className="flex">
                    <Button type="button" onClick={handleSaveCluster} disabled={!isK8sValidated || isSavingCluster}>
                      {isSavingCluster && <Loader2 className="size-4 animate-spin" />}
                      {t("env.save")}
                    </Button>
                  </div>
                  </TabsContent>

                  <TabsContent value="credentials" keepMounted className="flex flex-col gap-4">
                  {/* Image Repo Config Block */}
                  <div className="border rounded-lg overflow-hidden">
                    <div className="flex items-center justify-between px-4 py-3 bg-muted/50 border-b">
                      <div className="flex items-center gap-2">
                        <Package className="size-4 text-muted-foreground" />
                        <span className="text-sm font-semibold">{t("env.imageRepoConfig")}</span>
                      </div>
                      <Button
                        type="button"
                        variant={isRepoValidated ? "outline" : "secondary"}
                        size="sm"
                        onClick={handleValidateRepo}
                        disabled={isValidatingRepo || isRepoValidated}
                        className={isRepoValidated ? "text-success border-success hover:text-success" : ""}
                      >
                        {isValidatingRepo && <Loader2 className="size-3 animate-spin" />}
                        {isRepoValidated ? (
                          <>
                            <Check className="size-3" />
                            {t("env.validated")}
                          </>
                        ) : t("env.validate")}
                      </Button>
                    </div>
                    <div className="flex flex-col gap-4 p-4">
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
                                    className="absolute right-0 top-0 size-9 text-muted-foreground hover:bg-transparent"
                                    onClick={() => setShowRepoPassword(!showRepoPassword)}
                                  >
                                    {showRepoPassword ? (
                                      <EyeOff className="size-4" />
                                    ) : (
                                      <Eye className="size-4" />
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
                  </div>

                  {/* Git Credential Block */}
                  <div className="border rounded-lg overflow-hidden">
                    <div className="flex items-center gap-2 px-4 py-3 bg-muted/50 border-b">
                      <KeyRound className="size-4 text-muted-foreground" />
                      <span className="text-sm font-semibold">{t("env.gitCredential")}</span>
                    </div>
                    <div className="flex flex-col gap-4 p-4">
                      <p className="text-xs text-muted-foreground">{t("env.gitCredentialDesc")}</p>
                      <div className="grid grid-cols-2 gap-4">
                        <FormField
                          control={form.control}
                          name="gitCredential.username"
                          render={({ field }) => (
                            <FormItem>
                              <FormLabel>{t("env.gitUsername")}</FormLabel>
                              <FormControl>
                                <Input placeholder="username" {...field} />
                              </FormControl>
                              <FormMessage />
                            </FormItem>
                          )}
                        />
                        <FormField
                          control={form.control}
                          name="gitCredential.password"
                          render={({ field }) => (
                            <FormItem>
                              <FormLabel>{t("env.gitPassword")}</FormLabel>
                              <FormControl>
                                <div className="relative">
                                  <Input
                                    type={showGitPassword ? "text" : "password"}
                                    placeholder="token or password"
                                    {...field}
                                    className="pr-10"
                                  />
                                  <Button
                                    type="button"
                                    variant="ghost"
                                    size="icon"
                                    className="absolute right-0 top-0 size-9 text-muted-foreground hover:bg-transparent"
                                    onClick={() => setShowGitPassword(!showGitPassword)}
                                  >
                                    {showGitPassword ? (
                                      <EyeOff className="size-4" />
                                    ) : (
                                      <Eye className="size-4" />
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
                          name="gitCredential.privateKey"
                          render={({ field }) => (
                            <FormItem className="col-span-2">
                              <FormLabel>{t("env.gitPrivateKey")}</FormLabel>
                              <FormControl>
                                <div className="relative w-full">
                                  <Textarea
                                    placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"
                                    {...field}
                                    rows={3}
                                    className="pr-10 min-h-[unset] max-h-[80px] break-all resize-none overflow-y-auto"
                                    style={{
                                      WebkitTextSecurity: showGitPrivateKey ? 'none' : 'disc',
                                    } as CSSProperties}
                                  />
                                  <Button
                                    type="button"
                                    variant="ghost"
                                    size="icon"
                                    className="absolute right-0 top-0 size-9 text-muted-foreground hover:bg-transparent"
                                    onClick={() => setShowGitPrivateKey(!showGitPrivateKey)}
                                  >
                                    {showGitPrivateKey ? (
                                      <EyeOff className="size-4" />
                                    ) : (
                                      <Eye className="size-4" />
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
                  </div>

                  <div className="flex">
                    <Button type="button" onClick={handleSaveCredentials} disabled={!isRepoValidated || isSavingCredentials}>
                      {isSavingCredentials && <Loader2 className="size-4 animate-spin" />}
                      {t("env.save")}
                    </Button>
                  </div>
                  </TabsContent>

                  <TabsContent value="danger-zone">
                  {/* Danger Zone Block */}
                  <div className="border border-destructive/30 rounded-lg overflow-hidden">
                    <div className="flex items-center gap-2 px-4 py-3 bg-destructive/10 border-b border-destructive/30">
                      <AlertTriangle className="size-4 text-destructive" />
                      <span className="text-sm font-semibold text-destructive">{t("env.dangerZone")}</span>
                    </div>
                    <div className="flex items-center justify-between p-4 bg-destructive/5">
                      <div className="space-y-1">
                        <p className="text-sm font-medium">{t("env.delete")}</p>
                        <p className="text-xs text-muted-foreground">
                          {t("env.deleteDescPrefix")}
                          <strong>{environment}</strong>
                          {t("env.deleteDescSuffix")}
                        </p>
                      </div>
                      <Button
                        type="button"
                        variant="destructive"
                        size="sm"
                        onClick={() => {
                          setDeleteConfirmInput("")
                          setShowDeleteDialog(true)
                        }}
                      >
                        {t("env.delete")}
                      </Button>
                    </div>
                  </div>
                  </TabsContent>
                </Tabs>
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
              {t("env.deleteDescPrefix")}<strong>{environment}</strong>{t("env.deleteDescSuffix")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="space-y-2">
            <Input
              value={deleteConfirmInput}
              onChange={(e) => setDeleteConfirmInput(e.target.value)}
              placeholder={environment || t("env.namePlaceholder")}
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
              disabled={isDeleting || !environment || deleteConfirmInput !== environment}
              onClick={(e) => {
                e.preventDefault()
                handleDelete()
              }}
            >
              {isDeleting && <Loader2 className="size-4 animate-spin" />}
              {t("env.deleteConfirmBtn")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      </div>
    </ContentPage>
  )
}
