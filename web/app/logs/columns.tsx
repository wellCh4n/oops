"use client";

import type { ColumnDef } from "@tanstack/react-table";
import type { Log } from "@/lib/api/types";
import { LocalTime } from "@/components/ui/local-time";
import { Badge } from "@/components/ui/badge";

export const getColumns = (t: (key: string) => string): ColumnDef<Log>[] => [
  {
    accessorKey: "timestamp",
    header: t("log.timestamp"),
    cell: ({ row }) => <LocalTime value={row.original.timestamp} />,
  },
  {
    accessorKey: "username",
    header: t("log.user"),
    cell: ({ row }) => (
      <span className="font-medium">{row.original.username || row.original.userId || "-"}</span>
    ),
  },
  {
    accessorKey: "operation",
    header: t("log.operation"),
    cell: ({ row }) => {
      const operation = row.original.operation;
      const translated = t(`log.operations.${operation}`);
      return translated !== `log.operations.${operation}` ? translated : operation;
    },
  },
  {
    accessorKey: "resourceType",
    header: t("log.resource"),
    cell: ({ row }) => row.original.resourceType || "-",
  },
  {
    accessorKey: "namespace",
    header: t("log.namespace"),
    cell: ({ row }) => row.original.namespace || "-",
  },
  {
    accessorKey: "source",
    header: t("log.source"),
    cell: ({ row }) => (
      <Badge variant="outline" className="font-mono text-xs">
        {t(`log.sources.${row.original.source}`)}
      </Badge>
    ),
  },
  {
    accessorKey: "success",
    header: t("log.result"),
    cell: ({ row }) =>
      row.original.success ? (
        <Badge variant="success">{t("log.success")}</Badge>
      ) : (
        <Badge variant="destructive">{t("log.failed")}</Badge>
      ),
  },
  {
    accessorKey: "clientIp",
    header: t("log.clientIp"),
    cell: ({ row }) => row.original.clientIp || "-",
  },
];
