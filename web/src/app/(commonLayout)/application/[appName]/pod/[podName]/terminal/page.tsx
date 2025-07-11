'use client';

import { useEffect, useRef, useCallback } from 'react';
import { useParams } from 'next/navigation';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { AttachAddon } from '@xterm/addon-attach';
import '@xterm/xterm/css/xterm.css';

export default function TerminalPage() {
  const terminalRef = useRef<HTMLDivElement>(null);
  const terminalInstanceRef = useRef<Terminal | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const isInitializedRef = useRef(false);
  const params = useParams();
  const { appName, podName } = params;

  const initializeTerminal = useCallback(() => {
    if (isInitializedRef.current || !terminalRef.current) {
      return;
    }

    isInitializedRef.current = true;

    const terminal = new Terminal({
      cursorBlink: true,
      fontSize: 14,
      fontFamily: 'Monaco, Menlo, "Ubuntu Mono", monospace',
      theme: {
        background: '#1e1e1e',
        foreground: '#ffffff'
      }
    });

    terminalInstanceRef.current = terminal;

    const fitAddon = new FitAddon();
    terminal.loadAddon(fitAddon);

    terminal.open(terminalRef.current);
    fitAddon.fit();

    const socketURL = `ws://localhost:8080/api/namespaces/default/applications/${appName}/pods/${podName}/terminal`;
    const socket = new WebSocket(socketURL);
    socketRef.current = socket;

    socket.binaryType = 'arraybuffer';

    socket.onopen = () => {
      const attachAddon = new AttachAddon(socket, {bidirectional: true});
      terminal.loadAddon(attachAddon);
      terminal.writeln('🟢 WebSocket connected');
    };
  }, [appName, podName]);

  const cleanup = useCallback(() => {
    
    if (socketRef.current) {
      socketRef.current.close();
      socketRef.current = null;
    }
    
    if (terminalInstanceRef.current) {
      terminalInstanceRef.current.dispose();
      terminalInstanceRef.current = null;
    }
    
    isInitializedRef.current = false;
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => {
      initializeTerminal();
    }, 0);

    return () => {
      clearTimeout(timer);
      cleanup();
    };
  }, [initializeTerminal, cleanup]);

  useEffect(() => {
    if (isInitializedRef.current) {
      cleanup();
      const timer = setTimeout(() => {
        initializeTerminal();
      }, 100);
      
      return () => clearTimeout(timer);
    }
  }, [appName, podName, initializeTerminal, cleanup]);

  return (
    <div className="h-full w-full bg-black">
      <div 
        ref={terminalRef} 
        // className="h-full w-full"
      />
    </div>
  );
}