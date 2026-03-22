"use client"

import { useState, useEffect } from "react"
import { useRouter } from "next/navigation"
import { Plus, Search } from "lucide-react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { DataTable } from "@/components/ui/data-table"
import { columns, EnvironmentFormValues, environmentSchema } from "./columns"
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

export default function EnvironmentsPage() {
  const router = useRouter()
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [appliedSearch, setAppliedSearch] = useState("")

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

  const loadEnvironments = async () => {
    try {
      setIsLoading(true)
      const response = await fetchEnvironments()
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
          toast.success("环境创建成功")
          setDialogOpen(false)
          loadEnvironments()
      } else {
          toast.error("环境创建失败")
      }
    } catch (e) {
      console.error(e)
      toast.error("环境创建失败")
    }
  }

  const handleView = (env: Environment) => {
    router.push(`/settings/environments/${env.id}`)
  }

  return (
    <ContentPage title="环境">
      <TableForm
        options={
          <div className="flex items-center justify-between gap-4">
            <div className="flex items-center space-x-2">
              <span className="text-sm font-medium whitespace-nowrap">名称:</span>
              <Input
                placeholder="搜索名称..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") setAppliedSearch(search) }}
                className="w-56"
              />
              <Button variant="outline" onClick={() => setAppliedSearch(search)}>
                <Search className="mr-2 h-4 w-4" />
                搜索
              </Button>
            </div>
            <Button onClick={() => setDialogOpen(true)}>
              <Plus className="mr-2 h-4 w-4" />
              创建环境
            </Button>
          </div>
        }
        table={
          <DataTable
            columns={columns}
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
            <DialogTitle>创建环境</DialogTitle>
            <DialogDescription>
              输入环境名称以创建新环境。
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
                      <FormLabel>名称</FormLabel>
                      <FormControl>
                        <Input placeholder="Production" {...field} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>

              <DialogFooter>
                <Button type="submit">创建</Button>
              </DialogFooter>
            </form>
          </Form>
        </DialogContent>
      </Dialog>
    </ContentPage>
  )
}
