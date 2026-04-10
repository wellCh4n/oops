"use client"

import { useEffect } from "react"
import { useRouter } from "next/navigation"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { createApplication } from "@/lib/api/applications"
import { createApplicationSchema, CreateApplicationFormValues } from "../schema"
import { useLanguage } from "@/contexts/language-context"

interface ApplicationCreateDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  namespaces: { id: string; name: string }[]
  defaultNamespace: string
}

export function ApplicationCreateDialog({
  open,
  onOpenChange,
  namespaces,
  defaultNamespace,
}: ApplicationCreateDialogProps) {
  const router = useRouter()
  const { t } = useLanguage()

  const form = useForm<CreateApplicationFormValues>({
    resolver: zodResolver(createApplicationSchema),
    defaultValues: {
      name: "",
      namespace: defaultNamespace || "",
      description: "",
    },
  })

  useEffect(() => {
    if (!open) {
      return
    }

    form.reset({
      name: "",
      namespace: defaultNamespace || "",
      description: "",
    })
  }, [open, defaultNamespace, form])

  const onSubmitCreate = async (data: CreateApplicationFormValues) => {
    try {
      const payload = {
        ...data,
        workspaceId: data.namespace,
        repository: "", // Default empty as it's not in the simple form
        dockerFile: "Dockerfile",
        buildImage: "",
      }
      
      await createApplication(payload)
      toast.success(t("apps.create.success"))
      onOpenChange(false)

      // Redirect to the edit page (detail page)
      router.push(`/apps/${data.namespace}/${data.name}`)
    } catch (error) {
      console.error(error)
      toast.error(t("apps.create.error"))
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("apps.create.title")}</DialogTitle>
          <DialogDescription>
            {t("apps.create.description")}
          </DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmitCreate)} className="space-y-4">
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>{t("common.appName")}</FormLabel>
                  <FormControl>
                    <Input placeholder={t("apps.create.namePlaceholder")} {...field} />
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
                  <FormLabel>{t("common.namespace")}</FormLabel>
                  <Select onValueChange={field.onChange} defaultValue={field.value} value={field.value}>
                    <FormControl>
                      <SelectTrigger>
                        <SelectValue placeholder={t("apps.create.nsPlaceholder")} />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      {namespaces.map(ns => (
                        <SelectItem key={ns.id} value={ns.id}>{ns.name}</SelectItem>
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
                  <FormLabel>{t("common.description")}</FormLabel>
                  <FormControl>
                    <Textarea placeholder={t("apps.create.descPlaceholder")} {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
                {t("common.cancel")}
              </Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                {form.formState.isSubmitting ? t("apps.create.creating") : t("apps.create.submit")}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
