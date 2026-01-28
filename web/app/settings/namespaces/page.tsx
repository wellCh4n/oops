"use client"

import { useState, useEffect } from "react"
import { Plus } from "lucide-react"
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
import { fetchNamespaces, createNamespace } from "@/lib/api/namespaces"

const formSchema = z.object({
  name: z.string().min(1, "名称不能为空"),
})

export default function NamespacesPage() {
  const [namespaces, setNamespaces] = useState<string[]>([])
  const [dialogOpen, setDialogOpen] = useState(false)

  const form = useForm<z.infer<typeof formSchema>>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      name: "",
    },
  })

  const loadNamespaces = async () => {
    try {
      const res = await fetchNamespaces()
      if (res.success) {
        setNamespaces(res.data)
      } else {
        toast.error(res.message)
      }
    } catch (error) {
      toast.error("获取命名空间失败")
    }
  }

  useEffect(() => {
    loadNamespaces()
  }, [])

  const onSubmit = async (values: z.infer<typeof formSchema>) => {
    try {
      const res = await createNamespace(values.name)
      if (res.success) {
        toast.success("命名空间创建成功")
        setDialogOpen(false)
        form.reset()
        loadNamespaces()
      } else {
        toast.error(res.message)
      }
    } catch (error) {
      toast.error("创建命名空间失败")
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-2xl font-bold tracking-tight">命名空间</h2>
        <Button onClick={() => setDialogOpen(true)}>
          <Plus className="mr-2 h-4 w-4" />
          创建命名空间
        </Button>
      </div>

      <div className="border rounded-md">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>名称</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {namespaces.length === 0 ? (
               <TableRow>
                <TableCell colSpan={1} className="h-24 text-center">
                  暂无数据
                </TableCell>
              </TableRow>
            ) : (
              namespaces.map((ns) => (
                <TableRow key={ns}>
                  <TableCell>{ns}</TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

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
                      <Input placeholder="default" {...field} autoComplete="off" />
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
    </div>
  )
}
