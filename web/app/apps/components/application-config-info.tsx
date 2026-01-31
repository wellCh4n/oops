"use client"

import { useState, useEffect } from "react"
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
        toast.error("Failed to fetch config maps")
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
      toast.success("Config maps updated successfully")
    } catch (error) {
      toast.error("Failed to update config maps")
      console.error(error)
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <div className="space-y-6">
      <ApplicationEnvironmentSelector
        namespace={namespace}
        applicationName={applicationName}
        value={activeTab}
        onValueChange={setActiveTab}
        onEnvironmentsLoaded={handleEnvironmentsLoaded}
      >
        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
            <div className="rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[30%]">Key</TableHead>
                    <TableHead className="w-[30%]">Value</TableHead>
                    <TableHead className="w-[30%]">Mount Path</TableHead>
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
                                <Input placeholder="config.key" {...field} />
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
                                <Input placeholder="value" {...field} />
                              </FormControl>
                              <FormMessage />
                            </FormItem>
                          )}
                        />
                      </TableCell>
                      <TableCell>
                        <FormField
                          control={form.control}
                          name={`configMaps.${index}.mountPath`}
                          render={({ field }) => (
                            <FormItem>
                              <FormControl>
                                <Input placeholder="/etc/config/path" {...field} />
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
                      <TableCell colSpan={4} className="text-center text-muted-foreground h-24">
                        {isLoadingConfig ? "加载中..." : "暂无配置项"}
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
            </div>

            <div className="flex justify-between">
              <Button
                type="button"
                variant="outline"
                onClick={() => append({ key: "", value: "", mountPath: "" })}
                disabled={isLoadingConfig}
              >
                <Plus className="mr-2 h-4 w-4" />
                添加配置项
              </Button>
              <Button type="submit" disabled={isSaving || isLoadingConfig}>
                {isSaving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                保存配置
              </Button>
            </div>
          </form>
        </Form>
      </ApplicationEnvironmentSelector>
    </div>
  )
}
