"use client"

import { useState, useEffect } from "react"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { useRouter } from "next/navigation"
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
import { ApplicationBasicFormValues, applicationBasicSchema } from "../schema"
import { Application } from "@/lib/api/types"
import { updateApplication } from "@/lib/api/applications"
import { fetchNamespaces } from "@/lib/api/namespaces"

interface ApplicationBasicInfoProps {
  initialData?: Application
}

export function ApplicationBasicInfo({
  initialData,
}: ApplicationBasicInfoProps) {
  const [namespaces, setNamespaces] = useState<string[]>([])
  
  useEffect(() => {
    const loadNamespaces = async () => {
      try {
        const res = await fetchNamespaces()
        setNamespaces(res.data)
      } catch (error) {
        toast.error("Failed to fetch namespaces")
      }
    }
    loadNamespaces()
  }, [])

  const form = useForm<ApplicationBasicFormValues>({
    resolver: zodResolver(applicationBasicSchema),
    defaultValues: initialData ? {
      id: initialData.id,
      name: initialData.name,
      namespace: initialData.namespace,
      description: initialData.description,
    } : {
      name: "",
      namespace: "",
      description: "",
    },
    mode: "onChange",
  })

  const { isSubmitting } = form.formState

  const onSubmit = async (data: ApplicationBasicFormValues) => {
    try {
      const payload = {
        ...data,
        workspaceId: data.namespace
      }
      
      await updateApplication(payload)
      toast.success("应用更新成功")
    } catch (error) {
      console.error(error)
      toast.error("保存失败")
    }
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6 max-w-2xl">
        <FormField
          control={form.control}
          name="name"
          render={({ field }) => (
            <FormItem>
              <FormLabel>应用名称</FormLabel>
              <FormControl>
                <Input placeholder="输入应用名称" {...field} disabled={!!initialData} />
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
              <FormLabel>命名空间</FormLabel>
              <Select onValueChange={field.onChange} defaultValue={field.value} value={field.value} disabled={!!initialData}>
                <FormControl>
                  <SelectTrigger>
                    <SelectValue placeholder="选择命名空间" />
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
          name="description"
          render={({ field }) => (
            <FormItem>
              <FormLabel>描述</FormLabel>
              <FormControl>
                <Textarea placeholder="输入应用描述" {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />

        <div className="flex justify-end">
          <Button type="submit" disabled={isSubmitting}>
            保存基本信息
          </Button>
        </div>
      </form>
    </Form>
  )
}
