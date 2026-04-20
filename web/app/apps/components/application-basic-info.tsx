"use client"

import { useState, useEffect } from "react"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { toast } from "sonner"
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
import { Button } from "@/components/ui/button"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { ApplicationBasicFormValues, getApplicationBasicSchema } from "../schema"
import { Application, Environment, ApplicationEnvironment } from "@/lib/api/types"
import { updateApplication, getApplicationEnvironments, updateApplicationEnvironments } from "@/lib/api/applications"
import { fetchNamespaces } from "@/lib/api/namespaces"
import { fetchEnvironments } from "@/lib/api/environments"
import { fetchUsers, User } from "@/lib/api/users"
import { AppWindow, Layers, AlignLeft, Server, Check, User as UserIcon } from "lucide-react"
import { cn } from "@/lib/utils"
import { useLanguage } from "@/contexts/language-context"
import { Skeleton } from "@/components/ui/skeleton"

interface ApplicationBasicInfoProps {
  initialData?: Application
}

export function ApplicationBasicInfo({
  initialData,
}: ApplicationBasicInfoProps) {
  const [namespaces, setNamespaces] = useState<string[]>([])
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [selectedEnvNames, setSelectedEnvNames] = useState<string[]>([])
  const [users, setUsers] = useState<User[]>([])
  const [loading, setLoading] = useState(true)
  const { t } = useLanguage()

  useEffect(() => {
    const loadData = async () => {
      try {
        const requests: Promise<unknown>[] = [
          fetchNamespaces().then(res => setNamespaces(res.data.map((ns) => ns.name))),
          fetchEnvironments().then(res => setEnvironments(res.data)),
          fetchUsers().then(setUsers),
        ]
        if (initialData) {
          requests.push(
            getApplicationEnvironments(initialData.namespace, initialData.name)
              .then(res => setSelectedEnvNames(res.data.map(e => e.environmentName)))
          )
        }
        await Promise.all(requests)
      } catch {
        toast.error(t("apps.basic.fetchError"))
      } finally {
        setLoading(false)
      }
    }
    loadData()
  }, [initialData, t])

  const form = useForm<ApplicationBasicFormValues>({
    resolver: zodResolver(getApplicationBasicSchema(t)),
    defaultValues: initialData ? {
      id: initialData.id,
      name: initialData.name,
      namespace: initialData.namespace,
      description: initialData.description,
      owner: initialData.owner ?? "",
    } : {
      name: "",
      namespace: "",
      description: "",
      owner: "",
    },
    mode: "onChange",
  })

  const { isSubmitting } = form.formState

  const toggleEnv = (envName: string) => {
    setSelectedEnvNames(prev => 
        prev.includes(envName) 
            ? prev.filter(n => n !== envName)
            : [...prev, envName]
    )
  }

  const onSubmit = async (data: ApplicationBasicFormValues) => {
    try {
      const payload = {
        ...data,
        workspaceId: data.namespace
      }
      
      await updateApplication(payload)

      if (initialData) {
        const envPayload: ApplicationEnvironment[] = selectedEnvNames.map(envName => ({
            namespace: data.namespace,
            applicationName: data.name,
            environmentName: envName
        }))
        await updateApplicationEnvironments(data.namespace, data.name, envPayload)
      }

      toast.success(t("apps.basic.updateSuccess"))
    } catch (error) {
      console.error(error)
      toast.error(t("apps.basic.updateError"))
    }
  }

  if (loading) {
    return (
      <div className="flex max-w-4xl flex-col gap-6">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-24 w-full" />
        {initialData && <Skeleton className="h-20 w-full" />}
      </div>
    )
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="flex max-w-4xl flex-col gap-6">
        <FormField
          control={form.control}
          name="name"
          render={({ field }) => (
            <FormItem>
              <FormLabel className="flex items-center gap-1"><AppWindow className="h-3.5 w-3.5" />{t("common.appName")}</FormLabel>
              <FormControl>
                <Input placeholder={t("apps.basic.namePlaceholder")} {...field} disabled={!!initialData} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="namespace"
          render={({ field }) => (
            <FormItem>
              <FormLabel className="flex items-center gap-1"><Layers className="h-3.5 w-3.5" />{t("common.namespace")}</FormLabel>
              <Select onValueChange={field.onChange} defaultValue={field.value} value={field.value} disabled={!!initialData}>
                <FormControl>
                  <SelectTrigger>
                    <SelectValue placeholder={t("apps.basic.nsPlaceholder")} />
                  </SelectTrigger>
                </FormControl>
                <SelectContent>
                  {namespaces.map((ns) => (
                    <SelectItem key={ns} value={ns}>
                      {ns}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="owner"
          render={({ field }) => (
            <FormItem>
              <FormLabel className="flex items-center gap-1"><UserIcon className="h-3.5 w-3.5" />{t("common.owner")}</FormLabel>
              <Select
                onValueChange={(value) => field.onChange(value === "__none__" ? "" : value)}
                defaultValue={field.value || "__none__"}
                value={field.value || "__none__"}
              >
                <FormControl>
                  <SelectTrigger>
                    <SelectValue placeholder={t("common.selectOwner")} />
                  </SelectTrigger>
                </FormControl>
                <SelectContent>
                  <SelectItem value="__none__">{t("common.unassigned")}</SelectItem>
                  {users.map(user => (
                    <SelectItem key={user.id} value={user.id}>
                      {user.username} ({user.id})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <FormMessage />
            </FormItem>
          )}
        />

        <FormField
          control={form.control}
          name="description"
          render={({ field }) => (
            <FormItem>
              <FormLabel className="flex items-center gap-1"><AlignLeft className="h-3.5 w-3.5" />{t("common.description")}</FormLabel>
              <FormControl>
                <Textarea placeholder={t("apps.basic.descPlaceholder")} {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        {initialData && (
            <FormItem>
                <FormLabel className="flex items-center gap-1"><Server className="h-3.5 w-3.5" />{t("apps.basic.deployEnv")}</FormLabel>
                <div className="flex flex-wrap gap-3">
                    {environments.map(env => {
                        const selected = selectedEnvNames.includes(env.name)
                        return (
                            <div
                                key={env.id}
                                role="button"
                                tabIndex={0}
                                onClick={() => toggleEnv(env.name)}
                                onKeyDown={(e) => {
                                    if (e.key === "Enter" || e.key === " ") {
                                        e.preventDefault()
                                        toggleEnv(env.name)
                                    }
                                }}
                                className={cn(
                                    "rounded-lg border p-3 flex items-center justify-between cursor-pointer transition-colors select-none gap-3 min-w-[12rem]",
                                    selected
                                        ? "border-primary bg-primary/5 text-primary"
                                        : "border-border hover:bg-accent/50"
                                )}
                            >
                                <span className="text-sm font-medium">{env.name}</span>
                                {selected ? (
                                    <div className="flex h-5 w-5 items-center justify-center rounded-full bg-primary text-primary-foreground">
                                        <Check className="h-3 w-3" />
                                    </div>
                                ) : (
                                    <div className="h-5 w-5 rounded-full border border-muted-foreground/30" />
                                )}
                            </div>
                        )
                    })}
                </div>
            </FormItem>
        )}

        <div className="flex">
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? t("common.saving") : t("common.save")}
          </Button>
        </div>
      </form>
    </Form>
  )
}
