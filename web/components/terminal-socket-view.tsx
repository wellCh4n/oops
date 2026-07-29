"use client"

import { useEffect, useRef } from "react"
import { Terminal } from "@xterm/xterm"
import { FitAddon } from "@xterm/addon-fit"
import { WebLinksAddon } from "@xterm/addon-web-links"
import { useTheme } from "next-themes"
import "@xterm/xterm/css/xterm.css"
import { useLanguage } from "@/contexts/language-context"

export type TerminalConnectionStatus = "connecting" | "connected" | "disconnected"

interface TerminalSocketViewProps {
    /**
     * WebSocket URL for the terminal, already carrying its own query string — this component
     * appends `&terminal=<id>` to it.
     */
    endpoint: string
    onConnectionStatusChange?: (status: TerminalConnectionStatus) => void
    /**
     * Receives a "reconnect right now" callback so a host page can wire it to a retry button.
     * Store it in a ref: it is handed over during an effect, so calling setState here would loop.
     */
    registerRetry?: (retry: () => void) => void
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

/** Delay before the first connect, which also keeps React Strict Mode from opening two sockets. */
const INITIAL_CONNECT_DELAY_MS = 100
const RECONNECT_BASE_DELAY_MS = 1000
const RECONNECT_MAX_DELAY_MS = 10000

/**
 * Names one terminal for the lifetime of a mount. `crypto.randomUUID` is only defined in secure
 * contexts, and OOPS is regularly reached over plain HTTP on an internal network, so it cannot be
 * relied on here. The id only has to be unique among a user's own live terminals — the server scopes
 * it by user id before it can reach a shell — so falling back to `getRandomValues`, or to time and
 * `Math.random` on the oldest browsers, is enough.
 */
function createTerminalId(): string {
    if (typeof crypto !== "undefined") {
        if (typeof crypto.randomUUID === "function") {
            return crypto.randomUUID()
        }
        if (typeof crypto.getRandomValues === "function") {
            const bytes = crypto.getRandomValues(new Uint8Array(16))
            return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("")
        }
    }
    return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`
}

/**
 * An xterm view backed by a WebSocket that reconnects on its own.
 *
 * <p>Two things make a dropped connection survivable. The xterm instance is created once and kept
 * across reconnects, so scrollback is never lost — the terminal reconnects underneath the same
 * screen instead of the page reloading. And a stable per-mount terminal id travels with every
 * connection, which the server uses to hand back the very shell this terminal was using, with its
 * working directory and running command intact.
 *
 * <p>That id deliberately lives only in this component's memory: a reload or a second tab gets a new
 * id and therefore a new shell, while a reconnect reuses it. Unmounting closes the socket with a
 * normal close code, which is what tells the server this shell is finished rather than interrupted.
 */
export default function TerminalSocketView({
    endpoint,
    onConnectionStatusChange,
    registerRetry,
}: TerminalSocketViewProps) {
    const terminalRef = useRef<HTMLDivElement>(null)
    const xtermRef = useRef<Terminal | null>(null)
    const onConnectionStatusChangeRef = useRef(onConnectionStatusChange)
    const registerRetryRef = useRef(registerRetry)
    const { t } = useLanguage()
    const translateRef = useRef(t)
    const { resolvedTheme } = useTheme()
    const xtermTheme = resolvedTheme === "light" ? TERMINAL_THEMES.light : TERMINAL_THEMES.dark
    const xtermThemeRef = useRef(xtermTheme)

    useEffect(() => {
        onConnectionStatusChangeRef.current = onConnectionStatusChange
        registerRetryRef.current = registerRetry
        translateRef.current = t
    }, [onConnectionStatusChange, registerRetry, t])

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

        const fit = () => {
            if (xtermRef.current && terminalRef.current) {
                try {
                    fitAddon.fit()
                } catch (e) {
                    console.warn("Terminal fit failed:", e)
                }
            }
        }

        const initialFitTimeout = setTimeout(fit, INITIAL_CONNECT_DELAY_MS)
        window.addEventListener("resize", fit)
        term.focus()

        const updateStatus = (status: TerminalConnectionStatus) => {
            onConnectionStatusChangeRef.current?.(status)
        }

        // Dim so session notices never read as output from the shell itself.
        const writeNotice = (message: string) => {
            try {
                term.write(`\r\n\x1b[2m── ${message} ──\x1b[0m\r\n`)
            } catch {
                // The terminal is being torn down; the notice no longer matters.
            }
        }

        // Identifies the shell for the lifetime of this mount, and only this mount.
        const terminalId = createTerminalId()
        const url = `${endpoint}&terminal=${terminalId}`

        let socket: WebSocket | null = null
        let reconnectTimeout: ReturnType<typeof setTimeout> | null = null
        let attempts = 0
        let everConnected = false
        let disposed = false

        const connect = () => {
            if (disposed) return
            const currentSocket = new WebSocket(url)
            currentSocket.binaryType = "arraybuffer"
            socket = currentSocket

            currentSocket.onopen = () => {
                // Unmounted while the handshake was still in flight: close deliberately so the
                // server retires the shell instead of holding it for a reconnect that never comes.
                if (disposed) {
                    currentSocket.close(1000)
                    return
                }
                attempts = 0
                if (everConnected) {
                    writeNotice(translateRef.current("terminal.reconnected"))
                }
                everConnected = true
                updateStatus("connected")
                term.focus()
                fit()
            }

            currentSocket.onmessage = (event) => {
                if (disposed) return
                try {
                    if (typeof event.data === "string") {
                        term.write(event.data)
                    } else {
                        term.write(new Uint8Array(event.data))
                    }
                } catch (e) {
                    console.error("Terminal write error:", e)
                }
            }

            currentSocket.onclose = () => {
                if (disposed || socket !== currentSocket) return
                updateStatus("disconnected")
                if (attempts === 0) {
                    writeNotice(translateRef.current("terminal.connectionLost"))
                }
                scheduleReconnect()
            }

            currentSocket.onerror = () => {
                // Every error is followed by a close event, which owns the reconnect.
            }
        }

        // Backs off up to a ceiling and keeps trying: an outage long enough to exhaust a retry
        // budget is exactly when the user most needs their session back.
        const scheduleReconnect = () => {
            if (disposed || reconnectTimeout) return
            const delay = Math.min(RECONNECT_BASE_DELAY_MS * 2 ** attempts, RECONNECT_MAX_DELAY_MS)
            attempts += 1
            reconnectTimeout = setTimeout(() => {
                reconnectTimeout = null
                connect()
            }, delay)
        }

        const retryNow = () => {
            if (disposed) return
            if (reconnectTimeout) {
                clearTimeout(reconnectTimeout)
                reconnectTimeout = null
            }
            attempts = 0
            if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
                return
            }
            connect()
        }
        registerRetryRef.current?.(retryNow)

        updateStatus("connecting")
        const initialConnectTimeout = setTimeout(connect, INITIAL_CONNECT_DELAY_MS)

        term.onData((data) => {
            if (socket && socket.readyState === WebSocket.OPEN) {
                socket.send(data)
            }
        })

        return () => {
            disposed = true
            clearTimeout(initialFitTimeout)
            clearTimeout(initialConnectTimeout)
            if (reconnectTimeout) clearTimeout(reconnectTimeout)
            window.removeEventListener("resize", fit)
            // 1000 marks this as "the user is done", which retires the shell server-side. A dropped
            // network sends no close frame at all, and that is what keeps the shell alive.
            if (socket && socket.readyState === WebSocket.OPEN) {
                socket.close(1000)
            }
            term.dispose()
            xtermRef.current = null
        }
    }, [endpoint])

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
