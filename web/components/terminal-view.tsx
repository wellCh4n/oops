"use client"

import { useEffect, useRef, useState } from "react"
import { Terminal } from "@xterm/xterm"
import { FitAddon } from "@xterm/addon-fit"
import { WebLinksAddon } from "@xterm/addon-web-links"
import "@xterm/xterm/css/xterm.css"
import { API_BASE_URL } from "@/lib/api/config"
import { Badge } from "@/components/ui/badge"

interface TerminalViewProps {
    namespace: string
    name: string
    pod: string
    env: string
}

export default function TerminalView({ namespace, name, pod, env }: TerminalViewProps) {
    const terminalRef = useRef<HTMLDivElement>(null)
    const xtermRef = useRef<Terminal | null>(null)
    const [connectionStatus, setConnectionStatus] = useState<"connecting" | "connected" | "disconnected">("connecting")

    useEffect(() => {
        if (!terminalRef.current) return

        // Initialize xterm
        const term = new Terminal({
            cursorBlink: true,
            fontSize: 14,
            fontFamily: 'Menlo, Monaco, "Courier New", monospace',
            theme: {
                background: '#000000',
            }
        })
        xtermRef.current = term

        const fitAddon = new FitAddon()
        const webLinksAddon = new WebLinksAddon()

        term.loadAddon(fitAddon)
        term.loadAddon(webLinksAddon)
        term.open(terminalRef.current)
        
        // Use requestAnimationFrame to ensure the terminal is rendered before fitting
        // Use a small timeout to ensure layout is computed
        const initialFitTimeout = setTimeout(() => {
            if (xtermRef.current && terminalRef.current) {
                try {
                    fitAddon.fit()
                } catch (e) {
                    console.warn("Initial fit failed:", e)
                }
            }
        }, 100)

        // Handle resize
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
        
        // Initial focus
        term.focus()

        // WebSocket Connection
        const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
        // If API_BASE_URL is relative or absolute, handle protocol replacement
        const baseUrl = API_BASE_URL.startsWith('http')
            ? API_BASE_URL.replace(/^http/, 'ws')
            : `${wsProtocol}//${window.location.host}${API_BASE_URL}`

        const wsUrl = `${baseUrl}/api/namespaces/${namespace}/applications/${name}/pods/${pod}/terminal?environment=${env}`

        let ws: WebSocket | null = null

        // Use a small timeout to prevent double connection in React Strict Mode
        const connectTimeout = setTimeout(() => {
            ws = new WebSocket(wsUrl)
            ws.binaryType = 'arraybuffer'

            ws.onopen = () => {
                if (!xtermRef.current) return
                setConnectionStatus("connected")
                term.focus()
                // Fit again after connection just in case
                try {
                    fitAddon.fit()
                } catch (e) {}
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
                setConnectionStatus("disconnected")
                if (xtermRef.current) {
                    try {
                        term.write('\r\n\x1b[31mConnection closed\x1b[0m\r\n')
                    } catch (e) {}
                }
            }

            ws.onerror = (error) => {
                console.error('WebSocket error:', error)
                if (xtermRef.current) {
                    try {
                        term.write('\r\n\x1b[31mConnection error\x1b[0m\r\n')
                    } catch (e) {}
                }
            }
        }, 100)

        // Terminal Input
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
    }, [namespace, name, pod, env])

    const handleContainerClick = () => {
        if (xtermRef.current) {
            xtermRef.current.focus()
        }
    }

    return (
        <div className="flex flex-col h-screen bg-black">
            <div className="flex items-center gap-4 p-2 border-b border-gray-800 text-sm bg-background">
                <Badge variant="outline">Pod: {pod}</Badge>
                <Badge variant="outline">Environment: {env}</Badge>
                <Badge variant={connectionStatus === "connected" ? "default" : "destructive"}>
                    {connectionStatus}
                </Badge>
            </div>
            <div 
                className="flex-1 overflow-hidden p-2 cursor-text" 
                ref={terminalRef} 
                onClick={handleContainerClick}
            />
        </div>
    )
}
