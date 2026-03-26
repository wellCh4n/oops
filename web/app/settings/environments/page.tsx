"use client"

import { useState, useEffect } from "react"
import { useRouter } from "next/navigation"
import { Plus, Search } from "lucide-react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { DataTable } from "@/components/ui/data-table"
import { getColumns, getEnvironmentSchema, EnvironmentFormValues } from "./columns"
import { Environment } from "@/lib/api/types"
import { fetchEnvironments, createEnvironment } from "@/lib/api/environments"
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
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import { useLanguage } from "@/contexts/language-context"

export default function EnvironmentsPage() {
  const router = useRouter()
  const { t } = useLanguage()
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [appliedSearch, setAppliedSearch] = useState("")

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

  const loadEnvironments = async () => {
    try {
      setIsLoading(true)
      const response = await fetchEnvironments()
      if (response.success) {
        setEnvironments(response.data)
      } else {
        toast.error(response.message || t("env.fetchError"))
      }
    } catch (error) {
      toast.error(t("env.fetchError"))
      console.error(error)
    } finally {
      setIsLoading(false)
    }
  }

  useEffect(() => {
    loadEnvironments()
  }, [])

  useEffect(() => {
    if (!dialogOpen) {
      form.reset({
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
      })
    }
  }, [dialogOpen, form])

  const onSubmit = async (data: EnvironmentFormValues) => {
    try {
      const res = await createEnvironment(data)
      if (res.success) {
          toast.success(t("env.createSuccess"))
          setDialogOpen(false)
          loadEnvironments()
      } else {
          toast.error(t("env.createError"))
      }
    } catch (e) {
      console.error(e)
      toast.error(t("env.createError"))
    }
  }

  const handleView = (env: Environment) => {
    router.push(`/settings/environments/${env.id}`)
  }

  return (
    <ContentPage title={t("env.title")}>
      <TableForm
        options={
          <div className="flex items-center justify-between gap-4">
            <div className="flex items-center space-x-2">
              <span className="text-sm font-medium whitespace-nowrap">{t("env.searchLabel")}</span>
              <Input
                placeholder={t("env.searchPlaceholder")}
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") setAppliedSearch(search) }}
                className="w-56"
              />
              <Button variant="outline" onClick={() => setAppliedSearch(search)}>
                <Search className="mr-2 h-4 w-4" />
                {t("common.search")}
              </Button>
            </div>
            <Button onClick={() => setDialogOpen(true)}>
              <Plus className="mr-2 h-4 w-4" />
              {t("env.createBtn")}
            </Button>
          </div>
        }
        table={
          <DataTable
            columns={getColumns(t)}
            data={appliedSearch ? environments.filter(e => e.name.toLowerCase().includes(appliedSearch.toLowerCase())) : environments}
            loading={isLoading}
            meta={{
              onView: handleView,
            }}
          />
        }
      />

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="sm:max-w-[500px]">
          <DialogHeader>
            <DialogTitle>{t("env.createTitle")}</DialogTitle>
            <DialogDescription>
              {t("env.createDesc")}
            </DialogDescription>
          </DialogHeader>
          <Form {...form}>
            <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
              <div className="space-y-4">
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

              <DialogFooter>
                <Button type="submit">{t("env.create")}</Button>
              </DialogFooter>
            </form>
          </Form>
        </DialogContent>
      </Dialog>
    </ContentPage>
  )
}
