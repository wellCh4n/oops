"use client"

import { useState, useEffect } from "react"
import { Plus } from "lucide-react"
import { Button } from "@/components/ui/button"
import { toast } from "sonner"
import { DataTable } from "@/components/ui/data-table"
import { columns, EnvironmentFormValues, environmentSchema } from "./columns"
import { Environment } from "@/lib/api/types"
import { fetchEnvironments, createEnvironment, updateEnvironment, testEnvironment } from "@/lib/api/environments"
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
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingEnv, setEditingEnv] = useState<Environment | null>(null)

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
        toast.error(response.message || "Failed to fetch environments")
      }
    } catch (error) {
      toast.error("Failed to fetch environments")
      console.error(error)
    } finally {
      setIsLoading(false)
    }
  }

  useEffect(() => {
    loadEnvironments()
  }, [])

  useEffect(() => {
    if (editingEnv) {
      form.reset({
        ...editingEnv,
        id: editingEnv.id,
        imageRepositoryUsername: editingEnv.imageRepositoryUsername || "",
        imageRepositoryPassword: editingEnv.imageRepositoryPassword || "",
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
  }, [editingEnv, form, dialogOpen])

  const handleCreate = async (data: EnvironmentFormValues) => {
    try {
      const response = await createEnvironment(data)
      if (response.success) {
        setEnvironments([...environments, response.data])
        toast.success("Environment created successfully")
        setDialogOpen(false)
      } else {
        toast.error(response.message || "Failed to create environment")
      }
    } catch (error) {
      toast.error("Failed to create environment")
      console.error(error)
    }
  }

  const onSubmit = async (data: EnvironmentFormValues) => {
    if (editingEnv) {
      handleUpdate(data)
      setDialogOpen(false)
    } else {
      await handleCreate(data)
    }
  }

  const handleUpdate = async (data: EnvironmentFormValues) => {
    if (!editingEnv) return
    try {
      const response = await updateEnvironment(editingEnv.id, data)
      if (response.success) {
        const updatedEnv: Environment = {
          ...data,
          id: editingEnv.id,
        }
        setEnvironments(environments.map((env) => (env.id === editingEnv.id ? updatedEnv : env)))
        setEditingEnv(null)
        toast.success("Environment updated successfully")
      } else {
        toast.error(response.message || "Failed to update environment")
      }
    } catch (error) {
      toast.error("Failed to update environment")
      console.error(error)
    }
  }

  const handleDelete = (id: string) => {
    setEnvironments(environments.filter((env) => env.id !== id))
    toast.success("Environment deleted successfully")
  }

  const openCreateDialog = () => {
    setEditingEnv(null)
    setDialogOpen(true)
  }

  const openEditDialog = (env: Environment) => {
    setEditingEnv(env)
    setDialogOpen(true)
  }

  const handleTest = async (id: string) => {
    try {
      const response = await testEnvironment(id)
      if (response.success && response.data) {
        toast.success("测试通过")
      } else {
        toast.error("测试失败")
      }
    } catch (error) {
      toast.error("测试失败")
    }
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
          onEdit: openEditDialog,
          onDelete: handleDelete,
          onTest: handleTest
        }}
      />

      <Dialog open={dialogOpen} onOpenChange={(open) => {
        setDialogOpen(open)
        if (!open) setEditingEnv(null)
      }}>
        <DialogContent className="sm:max-w-[500px]">
          <DialogHeader>
            <DialogTitle>
              {editingEnv ? "Edit Environment" : "Create Environment"}
            </DialogTitle>
            <DialogDescription>
              {editingEnv
                ? "Make changes to your environment here."
                : "Add a new environment to your system."}
            </DialogDescription>
          </DialogHeader>
          <Form {...form}>
            <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
              <FormField
                control={form.control}
                name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Name</FormLabel>
                    <FormControl>
                      <Input placeholder="Production" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="apiServerUrl"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>API Server URL</FormLabel>
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
                  <FormItem>
                    <FormLabel>API Server Token</FormLabel>
                    <FormControl>
                      <Input type="password" placeholder="sk-..." {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <div className="grid grid-cols-2 gap-4">
                <FormField
                  control={form.control}
                  name="workNamespace"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Work Namespace</FormLabel>
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
                      <FormLabel>Build Storage Class</FormLabel>
                      <FormControl>
                        <Input placeholder="standard" {...field} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
              <FormField
                control={form.control}
                name="imageRepositoryUrl"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Image Repository URL</FormLabel>
                    <FormControl>
                      <Input placeholder="docker.io/my-repo" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <div className="grid grid-cols-2 gap-4">
                <FormField
                  control={form.control}
                  name="imageRepositoryUsername"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Image Repository Username</FormLabel>
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
                      <FormLabel>Image Repository Password</FormLabel>
                      <FormControl>
                        <Input type="password" placeholder="password" {...field} />
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />
              </div>
              <DialogFooter>
                <Button type="submit">Save changes</Button>
              </DialogFooter>
            </form>
          </Form>
        </DialogContent>
      </Dialog>
    </div>
  )
}
