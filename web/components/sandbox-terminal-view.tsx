"use client"

import { useEffect, useRef } from "react"
import { Terminal } from "@xterm/xterm"
import { FitAddon } from "@xterm/addon-fit"
import { WebLinksAddon } from "@xterm/addon-web-links"
import { useTheme } from "next-themes"
import "@xterm/xterm/css/xterm.css"
import { API_BASE_URL } from "@/lib/api/config"
import { getToken } from "@/lib/auth"

interface SandboxTerminalViewProps {
    sandboxId: string
    onConnectionStatusChange?: (status: "connecting" | "connected" | "disconnected") => void
}

const TERMINAL_THEMES = {
    dark: {
        background: "#000000",
        foreground: "#e5e5e5",
        cursor: "#e5e5e5",
        cursorAccent: "#000000",
        selectionBackground: "#3a3d41",
    },
    light: {
        background: "#ffffff",
        foreground: "#1f1f1f",
        cursor: "#1f1f1f",
        cursorAccent: "#ffffff",
        selectionBackground: "#cfe5ff",
    },
} as const

export default function SandboxTerminalView({ sandboxId, onConnectionStatusChange }: SandboxTerminalViewProps) {
    const terminalRef = useRef<HTMLDivElement>(null)
    const xtermRef = useRef<Terminal | null>(null)
    const onConnectionStatusChangeRef = useRef(onConnectionStatusChange)
    const { resolvedTheme } = useTheme()
    const xtermTheme = resolvedTheme === "light" ? TERMINAL_THEMES.light : TERMINAL_THEMES.dark
    const xtermThemeRef = useRef(xtermTheme)

    useEffect(() => {
        onConnectionStatusChangeRef.current = onConnectionStatusChange
    }, [onConnectionStatusChange])

    const updateStatus = (status: "connecting" | "connected" | "disconnected") => {
        onConnectionStatusChangeRef.current?.(status)
    }

    useEffect(() => {
        xtermThemeRef.current = xtermTheme
        if (xtermRef.current) {
            xtermRef.current.options.theme = xtermTheme
        }
    }, [xtermTheme])

    useEffect(() => {
        if (!terminalRef.current) return

        const term = new Terminal({
            cursorBlink: true,
            fontSize: 14,
            fontFamily: 'Menlo, Monaco, "Courier New", monospace',
            theme: xtermThemeRef.current,
        })
        xtermRef.current = term

        const fitAddon = new FitAddon()
        const webLinksAddon = new WebLinksAddon()

        term.loadAddon(fitAddon)
        term.loadAddon(webLinksAddon)
        term.open(terminalRef.current)

        const initialFitTimeout = setTimeout(() => {
            if (xtermRef.current && terminalRef.current) {
                try {
                    fitAddon.fit()
                } catch (e) {
                    console.warn("Initial fit failed:", e)
                }
            }
        }, 100)

        const handleResize = () => {
            if (xtermRef.current && terminalRef.current) {
                try {
                    fitAddon.fit()
                } catch (e) {
                    console.warn("Resize fit failed:", e)
                }
            }
        }
        window.addEventListener('resize', handleResize)

        term.focus()

        const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
        const baseUrl = API_BASE_URL.startsWith('http')
            ? API_BASE_URL.replace(/^http/, 'ws')
            : `${wsProtocol}//${window.location.host}${API_BASE_URL}`

        const wsUrl = `${baseUrl}/api/sandbox/instances/${sandboxId}/terminal?token=${getToken()}`

        let ws: WebSocket | null = null

        const connectTimeout = setTimeout(() => {
            ws = new WebSocket(wsUrl)
            ws.binaryType = 'arraybuffer'

            ws.onopen = () => {
                if (!xtermRef.current) return
                updateStatus("connected")
                term.focus()
                try {
                    fitAddon.fit()
                } catch {}
            }

            ws.onmessage = (event) => {
                if (!xtermRef.current) return
                try {
                    if (typeof event.data === 'string') {
                        term.write(event.data)
                    } else {
                        term.write(new Uint8Array(event.data))
                    }
                } catch (e) {
                    console.error("Terminal write error:", e)
                }
            }

            ws.onclose = () => {
                updateStatus("disconnected")
                if (xtermRef.current) {
                    try {
                        term.write('\r\n\x1b[31mConnection closed\x1b[0m\r\n')
                    } catch {}
                }
            }

            ws.onerror = (error) => {
                console.error('WebSocket error:', error)
                if (xtermRef.current) {
                    try {
                        term.write('\r\n\x1b[31mConnection error\x1b[0m\r\n')
                    } catch {}
                }
            }
        }, 100)

        term.onData((data) => {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(data)
            }
        })

        return () => {
            clearTimeout(initialFitTimeout)
            clearTimeout(connectTimeout)
            window.removeEventListener('resize', handleResize)
            if (ws) {
                ws.close()
            }
            term.dispose()
            xtermRef.current = null
        }
    }, [sandboxId])

    const handleContainerClick = () => {
        if (xtermRef.current) {
            xtermRef.current.focus()
        }
    }

    return (
        <div className="flex h-full min-h-0 flex-col">
            <div
                className="flex-1 min-h-0 p-4 overflow-hidden"
                style={{ backgroundColor: xtermTheme.background }}
            >
                <div
                    className="h-full w-full cursor-text"
                    ref={terminalRef}
                    role="presentation"
                    onClick={handleContainerClick}
                />
            </div>
        </div>
    )
}
