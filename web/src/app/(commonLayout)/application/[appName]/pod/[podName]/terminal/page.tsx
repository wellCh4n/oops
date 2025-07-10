'use client';

import { useEffect, useRef } from 'react';
import { useParams } from 'next/navigation';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { AttachAddon } from '@xterm/addon-attach';
import '@xterm/xterm/css/xterm.css';

export default function TerminalPage() {
  const terminalRef = useRef<HTMLDivElement>(null);
  const params = useParams();
  const { appName, podName } = params;

  useEffect(() => {
    if (!terminalRef.current) return;

    // 创建终端实例
    const terminal = new Terminal({
      cursorBlink: true,
      fontSize: 14,
      fontFamily: 'Monaco, Menlo, "Ubuntu Mono", monospace',
      theme: {
        background: '#1e1e1e',
        foreground: '#ffffff'
      }
    });

    // 创建并加载FitAddon
    const fitAddon = new FitAddon();
    terminal.loadAddon(fitAddon);

    // 打开终端
    terminal.open(terminalRef.current);
    fitAddon.fit();

    // 创建WebSocket连接
    const socketURL = `ws://localhost:8080/api/namespaces/default/applications/${appName}/pods/${podName}/terminal`;
    const socket = new WebSocket(socketURL);

    socket.binaryType = 'arraybuffer';

    // 创建并加载AttachAddon
    const attachAddon = new AttachAddon(socket);
    terminal.loadAddon(attachAddon);

    // 窗口大小调整函数
    // const resizeScreen = () => {
    //   fitAddon.fit();
    //   const { cols, rows } = terminal;
    //   if (socket.readyState === WebSocket.OPEN) {
    //     socket.send(JSON.stringify({ type: 'resize', cols, rows }));
    //   }
    // };

    // 监听窗口大小变化
    // window.addEventListener('resize', resizeScreen);
    
    // 初始调整大小
    // setTimeout(() => {
    //   resizeScreen();
    // }, 100);

    // 清理函数
    return () => {
    //   window.removeEventListener('resize', resizeScreen);
      socket.close();
      terminal.dispose();
    };
  }, [appName, podName]);

  return (
    <div className="h-full w-full bg-black">
      <div 
        ref={terminalRef} 
        className="h-full w-full"
        style={{ height: '100vh' }}
      />
    </div>
  );
}