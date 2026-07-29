"use client"

import { useMemo } from "react"
import { API_BASE_URL } from "@/lib/api/config"
import { getToken } from "@/lib/auth"
import TerminalSocketView, { type TerminalConnectionStatus } from "@/components/terminal-socket-view"

interface SandboxTerminalViewProps {
    sandboxId: string
    onConnectionStatusChange?: (status: TerminalConnectionStatus) => void
    registerRetry?: (retry: () => void) => void
}

export default function SandboxTerminalView({
    sandboxId,
    onConnectionStatusChange,
    registerRetry,
}: SandboxTerminalViewProps) {
    const endpoint = useMemo(() => {
        const wsProtocol = window.location.protocol === "https:" ? "wss:" : "ws:"
        // API_BASE_URL is absolute in dev and same-origin in production; both have to end up as ws(s).
        const baseUrl = API_BASE_URL.startsWith("http")
            ? API_BASE_URL.replace(/^http/, "ws")
            : `${wsProtocol}//${window.location.host}${API_BASE_URL}`
        return `${baseUrl}/api/sandbox/instances/${sandboxId}/terminal?token=${getToken()}`
    }, [sandboxId])

    return (
        <TerminalSocketView
            endpoint={endpoint}
            onConnectionStatusChange={onConnectionStatusChange}
            registerRetry={registerRetry}
        />
    )
}
