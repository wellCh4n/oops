"use client"

import { useState, useEffect } from "react"
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

const formSchema = z.object({
  name: z.string().min(1, "名称不能为空"),
  description: z.string().optional(),
})

export default function NamespacesPage() {
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

  const loadNamespaces = async () => {
    setLoading(true)
    try {
      const res = await fetchNamespaces()
      if (res.success) {
        setNamespaces(res.data)
      } else {
        toast.error(res.message)
      }
    } catch {
      toast.error("获取命名空间失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadNamespaces()
  }, [])

  const onSubmit = async (values: z.infer<typeof formSchema>) => {
    try {
      const res = await createNamespace(values.name, values.description)
      if (res.success) {
        toast.success("命名空间创建成功")
        setDialogOpen(false)
        form.reset()
        loadNamespaces()
      } else {
        toast.error(res.message)
      }
    } catch {
      toast.error("创建命名空间失败")
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
        toast.success("命名空间更新成功")
        setEditDialogOpen(false)
        setEditingNamespace(null)
        editForm.reset()
        loadNamespaces()
      } else {
        toast.error(res.message)
      }
    } catch {
      toast.error("更新命名空间失败")
    }
  }

  return (
    <ContentPage title="命名空间">
      <TableForm
        options={
          <div className="flex items-center justify-between gap-4">
            <div className="flex items-center space-x-2">
              <span className="text-sm font-medium whitespace-nowrap">名称或描述:</span>
              <Input
                placeholder="搜索名称或描述..."
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
              创建命名空间
            </Button>
          </div>
        }
        table={
          <div className="border rounded-md">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>名称</TableHead>
                  <TableHead>描述</TableHead>
                  <TableHead className="text-right"></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {loading ? (
                  <TableRow>
                    <TableCell colSpan={3} className="py-2 text-center text-muted-foreground">
                      加载中...
                    </TableCell>
                  </TableRow>
                ) : namespaces.filter(ns =>
                    !appliedSearch ||
                    ns.name.toLowerCase().includes(appliedSearch.toLowerCase()) ||
                    (ns.description ?? "").toLowerCase().includes(appliedSearch.toLowerCase())
                  ).length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={3} className="h-24 text-center text-muted-foreground">
                      暂无数据
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
                            title="编辑"
                          >
                            <Pencil className="mr-2 h-4 w-4" />
                            编辑
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
            <DialogTitle>创建命名空间</DialogTitle>
            <DialogDescription>
              添加一个新的命名空间到系统。
            </DialogDescription>
          </DialogHeader>
          <Form {...form}>
            <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
              <FormField
                control={form.control}
                name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>名称</FormLabel>
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
                    <FormLabel>描述</FormLabel>
                    <FormControl>
                      <Input {...field} autoComplete="off" />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <DialogFooter>
                <Button type="submit">保存</Button>
              </DialogFooter>
            </form>
          </Form>
        </DialogContent>
      </Dialog>

      <Dialog open={editDialogOpen} onOpenChange={setEditDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>编辑命名空间</DialogTitle>
            <DialogDescription>
              更新命名空间的描述信息。
            </DialogDescription>
          </DialogHeader>
          <Form {...editForm}>
            <form onSubmit={editForm.handleSubmit(onEditSubmit)} className="space-y-4">
              <FormField
                control={editForm.control}
                name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>名称</FormLabel>
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
                    <FormLabel>描述</FormLabel>
                    <FormControl>
                      <Input {...field} autoComplete="off" />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <DialogFooter>
                <Button type="submit">保存</Button>
              </DialogFooter>
            </form>
          </Form>
        </DialogContent>
      </Dialog>
    </ContentPage>
  )
}
