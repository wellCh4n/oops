'use client';

import { useEffect, useRef, useState } from 'react';
import { useParams } from 'next/navigation';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { AttachAddon } from '@xterm/addon-attach';
import '@xterm/xterm/css/xterm.css';
import { openApplicationPodExplorer, openApplicationPodTerminal } from '@/service/application';
import { useHeader } from '@/context/header-context';
import { Tree, TreeDataNode } from 'antd';
import { ApplicationPodFileDirectory } from '@/types/application';


function updateTreeData(
  list: TreeDataNode[],
  key: React.Key,
  children: TreeDataNode[]
): TreeDataNode[] {
  return list.map((node) => {
    if (node.key === key) {
      return {
        ...node,
        children
      };
    } else if (node.children) {
      return {
        ...node,
        children: updateTreeData(node.children, key, children)
      };
    }
    return node;
  });
}

export default function TerminalPage() {
  const terminalRef = useRef<HTMLDivElement>(null);
  const [terminalInstance, setTerminalInstance] = useState<Terminal | null>(null);

  const [fileTree, setFileTree] = useState<TreeDataNode[]>([]);
  const [fileSocket, setFileSocket] = useState<WebSocket | null>(null);

  const { appName, podName } = useParams<{ appName: string; podName: string }>();
  const { setHeaderContent } = useHeader();

  useEffect(() => {
    setHeaderContent(`Application Terminal: ${appName} - ${podName}`);
  }, [setHeaderContent, appName, podName]);

  useEffect(() => {
    if (!terminalRef.current) return;

    const terminal = new Terminal({
      cursorBlink: true,
      fontSize: 14,
      fontFamily: 'Monaco, Menlo, "Ubuntu Mono", monospace',
      theme: {
        background: '#1e1e1e',
        foreground: '#ffffff'
      }
    });
    setTerminalInstance(terminal);

    const fitAddon = new FitAddon();
    terminal.loadAddon(fitAddon);
    terminal.open(terminalRef.current);
    fitAddon.fit();

    return () => {
      terminal.dispose();
    };
  }, []);

  useEffect(() => {
    if (!terminalInstance) return;

    const socket = openApplicationPodTerminal(appName, podName);
    socket.binaryType = 'arraybuffer';

    socket.onopen = () => {
      const attachAddon = new AttachAddon(socket, { bidirectional: true });
      terminalInstance.loadAddon(attachAddon);
    };

    return () => {
      socket.close();
    };
  }, [terminalInstance, appName, podName]);

  useEffect(() => {
    if (!terminalInstance) return;

    const socket = openApplicationPodExplorer(appName, podName);
    socket.onopen = () => {
      socket.send('/');
    };
    setFileSocket(socket);

    socket.onmessage = (event) => {
      const directory = JSON.parse(event.data) as ApplicationPodFileDirectory;
      const children = directory.items.map((file) => ({
        title: file?.name,
        key: file?.absolutePath,
        isLeaf: !file?.directory,
        children: file?.directory ? [] : undefined
      }));

      setFileTree((origin) => {
        if (origin.length === 0) {
          return children;
        } else {
          return updateTreeData(origin, directory.pwd, children);
        }
      });
    };

    return () => {
      socket.close();
      setFileTree([]);
    };
  }, [terminalInstance, appName, podName]);

  return (
    <div className="flex h-full">
      <div className="w-[400px] overflow-auto p-3 bg-white">
        <Tree.DirectoryTree
          showLine
          blockNode
          loadData={async (item) => {
            const path = item.key as string;
            return new Promise<void>((resolve) => {
              fileSocket?.send(path);
              resolve();
            });
          }}
          onSelect={(selectedKeys, info) => {
            console.log(selectedKeys, info)
          }}
          treeData={fileTree}
        />
      </div>
      <div className="h-full w-full" style={{ backgroundColor: '#1e1e1e' }}>
        <div className="h-full p-2" ref={terminalRef} />
      </div>
    </div>
  );
}