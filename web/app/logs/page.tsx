"use client";

import { useState, useEffect } from "react";
import { ChevronLeft, ChevronRight, RotateCcw } from "lucide-react";
import { useLanguage } from "@/contexts/language-context";
import { DataTable } from "@/components/ui/data-table";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ContentPage } from "@/components/content-page";
import { TableForm } from "@/components/ui/table-form";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { getLogs, type LogsQueryParams } from "@/lib/api/logs";
import type { Log, Page } from "@/lib/api/types";
import { getColumns } from "./columns";

export default function LogsPage() {
  const { t } = useLanguage();
  const [data, setData] = useState<Page<Log>>({
    data: [],
    total: 0,
    size: 20,
    totalPages: 0,
  });
  const [loading, setLoading] = useState(false);
  const [initialLoad, setInitialLoad] = useState(true);
  const [filters, setFilters] = useState<LogsQueryParams>({
    page: 1,
    size: 20,
  });

  const fetchLogs = async () => {
    setLoading(true);
    try {
      const result = await getLogs(filters);
      if (result.success) {
        setData(result.data);
      }
    } catch (error) {
      console.error("Failed to fetch logs:", error);
    } finally {
      setLoading(false);
      setInitialLoad(false);
    }
  };

  useEffect(() => {
    fetchLogs();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters]);

  const handleSearch = (field: keyof LogsQueryParams, value: string) => {
    setFilters((prev) => ({
      ...prev,
      [field]: value || undefined,
      page: 1,
    }));
  };

  const handlePageChange = (page: number) => {
    setFilters((prev) => ({ ...prev, page }));
  };

  const page = filters.page || 1;
  const totalPages = data.totalPages || 1;

  return (
    <ContentPage title={t("log.title")}>
      <TableForm
        options={
          <div className="flex items-end justify-between flex-wrap gap-4">
            <div className="flex items-center gap-2 flex-wrap">
              <Input
                placeholder={t("log.filterByNamespace")}
                onChange={(e) => handleSearch("namespace", e.target.value)}
                className="w-[200px]"
              />
              <Input
                placeholder={t("log.filterByResourceType")}
                onChange={(e) => handleSearch("resourceType", e.target.value)}
                className="w-[200px]"
              />
              <Input
                placeholder={t("log.filterByUser")}
                onChange={(e) => handleSearch("userId", e.target.value)}
                className="w-[200px]"
              />
            </div>
            <Button variant="outline" onClick={fetchLogs} disabled={loading}>
              <RotateCcw className={`size-4 ${loading ? "animate-spin" : ""}`} />
              {t("common.refresh")}
            </Button>
          </div>
        }
        table={
          <>
            <div className="overflow-x-auto">
              <DataTable columns={getColumns(t)} data={data.data} loading={initialLoad} />
            </div>
            <div className="flex items-center justify-end gap-4 mt-2">
              <div className="flex items-center gap-2">
                <span className="text-sm text-muted-foreground">{t("common.pageSize")}</span>
                <Select
                  value={String(filters.size || 20)}
                  onValueChange={(v) =>
                    setFilters((prev) => ({ ...prev, size: Number(v), page: 1 }))
                  }
                  disabled={loading}
                >
                  <SelectTrigger className="w-[70px] h-8">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="10">10</SelectItem>
                    <SelectItem value="20">20</SelectItem>
                    <SelectItem value="50">50</SelectItem>
                  </SelectContent>
                </Select>
                <span className="text-sm text-muted-foreground">{t("common.pageSizeSuffix")}</span>
              </div>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page <= 1 || loading}
                  onClick={() => handlePageChange(page - 1)}
                >
                  <ChevronLeft className="size-4" />
                  {t("common.prevPage")}
                </Button>
                <span className="text-sm text-muted-foreground">
                  {t("common.pagePrefix")}{page}{t("common.pageSuffix")} / {t("common.totalPages").replace("${total}", String(totalPages))}
                </span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page >= totalPages || loading}
                  onClick={() => handlePageChange(page + 1)}
                >
                  {t("common.nextPage")}
                  <ChevronRight className="ml-2 size-4" />
                </Button>
              </div>
            </div>
          </>
        }
      />
    </ContentPage>
  );
}
