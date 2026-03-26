"use client"

import { useState, useEffect, useLayoutEffect, useRef } from "react"
import { useForm, useFieldArray } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import { Plus, Trash2, Loader2 } from "lucide-react"
import { toast } from "sonner"

import { ApplicationEnvironmentSelector } from "./application-environment-selector"
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { getApplicationConfigMaps, updateApplicationConfigMaps } from "@/lib/api/applications"
import { ApplicationEnvironment } from "@/lib/api/types"
import { ApplicationConfigFormValues, applicationConfigSchema } from "../schema"
import { useLanguage } from "@/contexts/language-context"

function ConfigValueTextarea({
  value,
  onChange,
  disabled,
}: {
  value: string
  onChange: (value: string) => void
  disabled?: boolean
}) {
  const ref = useRef<HTMLTextAreaElement | null>(null)

  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    el.style.height = "auto"
    el.style.height = `${el.scrollHeight}px`
  }, [value])

  return (
    <textarea
      ref={ref}
      value={value}
      disabled={disabled}
      placeholder="value"
      rows={1}
      className="border-input placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-ring/50 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive dark:bg-input/30 flex w-full rounded-md border bg-transparent px-3 py-2 text-base shadow-xs outline-none transition-[color,box-shadow] focus-visible:ring-[3px] disabled:cursor-not-allowed disabled:opacity-50 md:text-sm resize-none overflow-hidden"
      onChange={(e) => {
        e.currentTarget.style.height = "auto"
        e.currentTarget.style.height = `${e.currentTarget.scrollHeight}px`
        onChange(e.currentTarget.value)
      }}
    />
  )
}

interface ApplicationConfigInfoProps {
  applicationName?: string
  namespace?: string
}

export function ApplicationConfigInfo({
  applicationName,
  namespace,
}: ApplicationConfigInfoProps) {
  // Removed environments state as it's handled by selector
  const [activeTab, setActiveTab] = useState<string>("")
  const [isLoadingConfig, setIsLoadingConfig] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const { t } = useLanguage()

  const form = useForm<ApplicationConfigFormValues>({
    resolver: zodResolver(applicationConfigSchema),
    defaultValues: {
      configMaps: [],
    },
  })

  const { fields, append, remove } = useFieldArray({
    control: form.control,
    name: "configMaps",
  })

  const handleEnvironmentsLoaded = (envs: ApplicationEnvironment[]) => {
    if (envs.length > 0 && !activeTab) {
      setActiveTab(envs[0].environmentName)
    }
  }

  useEffect(() => {
    const fetchConfigMaps = async () => {
      if (!namespace || !applicationName || !activeTab) return

      setIsLoadingConfig(true)
      try {
        const res = await getApplicationConfigMaps(namespace, applicationName, activeTab)
        if (res.data) {
          form.reset({ configMaps: res.data })
        }
      } catch (error) {
        toast.error(t("apps.config.fetchError"))
        console.error(error)
      } finally {
        setIsLoadingConfig(false)
      }
    }

    fetchConfigMaps()
  }, [namespace, applicationName, activeTab, form])

  const onSubmit = async (data: ApplicationConfigFormValues) => {
    if (!namespace || !applicationName || !activeTab) return

    setIsSaving(true)
    try {
      await updateApplicationConfigMaps(namespace, applicationName, activeTab, data.configMaps)
      toast.success(t("apps.config.updateSuccess"))
    } catch (error) {
      toast.error(t("apps.config.updateError"))
      console.error(error)
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <ApplicationEnvironmentSelector
        namespace={namespace}
        applicationName={applicationName}
        value={activeTab}
        onValueChange={setActiveTab}
        onEnvironmentsLoaded={handleEnvironmentsLoaded}
      >
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="flex flex-col gap-4">
            <div className="rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[45%]">Key</TableHead>
                    <TableHead className="w-[45%]">Value</TableHead>
                    <TableHead className="w-[10%]"></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {fields.map((field, index) => (
                    <TableRow key={field.id}>
                      <TableCell>
                        <FormField
                          control={form.control}
                          name={`configMaps.${index}.key`}
                          render={({ field }) => (
                            <FormItem>
                              <FormControl>
                                <Input placeholder="key" {...field} />
                              </FormControl>
                              <FormMessage />
                            </FormItem>
                          )}
                        />
                      </TableCell>
                      <TableCell>
                        <FormField
                          control={form.control}
                          name={`configMaps.${index}.value`}
                          render={({ field }) => (
                            <FormItem>
                              <FormControl>
                                <ConfigValueTextarea
                                  value={(field.value ?? "") as string}
                                  disabled={field.disabled}
                                  onChange={field.onChange}
                                />
                              </FormControl>
                              <FormMessage />
                            </FormItem>
                          )}
                        />
                      </TableCell>
                      <TableCell>
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          onClick={() => remove(index)}
                        >
                          <Trash2 className="h-4 w-4 text-destructive" />
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                  {fields.length === 0 && (
                    <TableRow>
                      <TableCell colSpan={3} className="text-center text-muted-foreground h-12">
                        {isLoadingConfig ? t("common.loading") : t("apps.config.noConfig")}
                      </TableCell>
                    </TableRow>
                  )}
                  <TableRow>
                    <TableCell colSpan={3}>
                      <Button
                        type="button"
                        variant="outline"
                        className="w-full justify-center"
                        onClick={() => append({ key: "", value: "" })}
                        disabled={isLoadingConfig}
                      >
                        <Plus className="mr-2 h-4 w-4" />
                        {t("apps.config.addItem")}
                      </Button>
                    </TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </div>

            <div className="flex">
              <Button type="submit" disabled={isSaving || isLoadingConfig}>
                {isSaving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                {t("common.save")}
              </Button>
            </div>
          </form>
        </Form>
      </ApplicationEnvironmentSelector>
    </div>
  )
}
