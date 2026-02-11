"use client"

import { useState, useEffect } from "react"
import { useRouter } from "next/navigation"
import { Plus } from "lucide-react"
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

export default function EnvironmentsPage() {
  const router = useRouter()
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)

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
      // Create
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

  const openCreateDialog = () => {
    setDialogOpen(true)
  }

  const handleView = (env: Environment) => {
    router.push(`/settings/environments/${env.id}`)
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
          onView: handleView,
        }}
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
    </div>
  )
}
