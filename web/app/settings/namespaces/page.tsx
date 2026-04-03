"use client"

import { useState, useEffect, useCallback } from "react"
import { Plus, Pencil, Search } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
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
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import * as z from "zod"
import { toast } from "sonner"
import { fetchNamespaces, createNamespace, updateNamespace } from "@/lib/api/namespaces"
import { Namespace } from "@/lib/api/types"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import { useLanguage } from "@/contexts/language-context"
import { useNamespaceStore } from "@/store/namespace"

export default function NamespacesPage() {
  const { t } = useLanguage()

  const formSchema = z.object({
    name: z.string().min(1, t("ns.nameRequired")),
    description: z.string().optional(),
  })
  const [namespaces, setNamespaces] = useState<Namespace[]>([])
  const [loading, setLoading] = useState(true)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [search, setSearch] = useState("")
  const [appliedSearch, setAppliedSearch] = useState("")
  const [editDialogOpen, setEditDialogOpen] = useState(false)
  const [editingNamespace, setEditingNamespace] = useState<Namespace | null>(null)

  const form = useForm<z.infer<typeof formSchema>>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      name: "",
      description: "",
    },
  })

  const editForm = useForm<z.infer<typeof formSchema>>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      name: "",
      description: "",
    },
  })

  const loadNamespaces = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetchNamespaces()
      if (res.success) {
        setNamespaces(res.data)
      } else {
        toast.error(res.message)
      }
    } catch {
      toast.error(t("ns.fetchError"))
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => {
    void loadNamespaces()
  }, [loadNamespaces])

  const onSubmit = async (values: z.infer<typeof formSchema>) => {
    try {
      const res = await createNamespace(values.name, values.description)
      if (res.success) {
        toast.success(t("ns.createSuccess"))
        setDialogOpen(false)
        form.reset()
        loadNamespaces()
        useNamespaceStore.getState().reload()
      } else {
        toast.error(res.message)
      }
    } catch {
      toast.error(t("ns.createError"))
    }
  }

  const handleEdit = (namespace: Namespace) => {
    setEditingNamespace(namespace)
    editForm.reset({
      name: namespace.name,
      description: namespace.description || "",
    })
    setEditDialogOpen(true)
  }

  const onEditSubmit = async (values: z.infer<typeof formSchema>) => {
    if (!editingNamespace) return

    try {
      const res = await updateNamespace(editingNamespace.name, values.description || "")
      if (res.success) {
        toast.success(t("ns.updateSuccess"))
        setEditDialogOpen(false)
        setEditingNamespace(null)
        editForm.reset()
        loadNamespaces()
        useNamespaceStore.getState().reload()
      } else {
        toast.error(res.message)
      }
    } catch {
      toast.error(t("ns.updateError"))
    }
  }

  return (
    <ContentPage title={t("ns.title")}>
      <TableForm
        options={
          <div className="flex items-end justify-between gap-4">
            <div className="flex flex-col gap-1.5">
              <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Search className="w-4 h-4" />{t("ns.searchLabel")}</span>
              <div className="flex items-center space-x-2">
                <Input
                  placeholder={t("ns.searchPlaceholder")}
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") setAppliedSearch(search) }}
                  className="w-56"
                />
                <Button variant="outline" onClick={() => setAppliedSearch(search)}>
                  <Search className="h-4 w-4" />
                  {t("common.search")}
                </Button>
              </div>
            </div>
            <Button onClick={() => setDialogOpen(true)}>
              <Plus className="h-4 w-4" />
              {t("ns.createBtn")}
            </Button>
          </div>
        }
        table={
          <div className="border rounded-md">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("ns.col.name")}</TableHead>
                  <TableHead>{t("common.description")}</TableHead>
                  <TableHead className="text-right"></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {loading ? (
                  <TableRow>
                    <TableCell colSpan={3} className="py-2 text-center text-muted-foreground">
                      {t("common.loading")}
                    </TableCell>
                  </TableRow>
                ) : namespaces.filter(ns =>
                    !appliedSearch ||
                    ns.name.toLowerCase().includes(appliedSearch.toLowerCase()) ||
                    (ns.description ?? "").toLowerCase().includes(appliedSearch.toLowerCase())
                  ).length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={3} className="h-24 text-center text-muted-foreground">
                      {t("common.noData")}
                    </TableCell>
                  </TableRow>
                ) : (
                  namespaces.filter(ns =>
                    !appliedSearch ||
                    ns.name.toLowerCase().includes(appliedSearch.toLowerCase()) ||
                    (ns.description ?? "").toLowerCase().includes(appliedSearch.toLowerCase())
                  ).map((ns) => (
                    <TableRow key={ns.id}>
                      <TableCell>{ns.name}</TableCell>
                      <TableCell>{ns.description || "-"}</TableCell>
                      <TableCell className="text-right">
                        <div className="flex items-center justify-end gap-2">
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => handleEdit(ns)}
                            title={t("common.edit")}
                          >
                            <Pencil className="h-4 w-4" />
                            {t("common.edit")}
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </div>
        }
      />

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("ns.createTitle")}</DialogTitle>
            <DialogDescription>
              {t("ns.createDesc")}
            </DialogDescription>
          </DialogHeader>
          <Form {...form}>
            <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
              <FormField
                control={form.control}
                name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>{t("ns.col.name")}</FormLabel>
                    <FormControl>
                      <Input {...field} autoComplete="off" />
                    </FormControl>
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
                      <Input {...field} autoComplete="off" />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <DialogFooter>
                <Button type="submit">{t("common.save")}</Button>
              </DialogFooter>
            </form>
          </Form>
        </DialogContent>
      </Dialog>

      <Dialog open={editDialogOpen} onOpenChange={setEditDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("ns.editTitle")}</DialogTitle>
            <DialogDescription>
              {t("ns.editDesc")}
            </DialogDescription>
          </DialogHeader>
          <Form {...editForm}>
            <form onSubmit={editForm.handleSubmit(onEditSubmit)} className="space-y-4">
              <FormField
                control={editForm.control}
                name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>{t("ns.col.name")}</FormLabel>
                    <FormControl>
                      <Input {...field} disabled autoComplete="off" />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={editForm.control}
                name="description"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>{t("common.description")}</FormLabel>
                    <FormControl>
                      <Input {...field} autoComplete="off" />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <DialogFooter>
                <Button type="submit">{t("common.save")}</Button>
              </DialogFooter>
            </form>
          </Form>
        </DialogContent>
      </Dialog>
    </ContentPage>
  )
}
