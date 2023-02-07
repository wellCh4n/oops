/* eslint-disable @typescript-eslint/no-unused-vars */
import { DND_RENDER_ID, NODE_WIDTH, NODE_HEIGHT } from './constant'
import { uuidv4, NsGraph, NsGraphStatusCommand } from '@antv/xflow'
import type { NsRenameNodeCmd } from './cmd-extensions/cmd-rename-node-modal'
import type { NsNodeCmd, NsEdgeCmd, NsGraphCmd } from '@antv/xflow'
import type { NsDeployDagCmd } from './cmd-extensions/cmd-deploy'
import request from 'umi-request';
import { message } from 'antd'
import {ApplicationPipeEdge, ApplicationPipeRelation, ApplicationPipeVertex} from './data'


const applicationPipeLineUrl = '/oops/api/application/pipe/line';
const putPipeLineUrl = '/oops/api/application/pipe/put';
/** mock 后端接口调用 */
export namespace MockApi {
  export const NODE_COMMON_PROPS = {
    renderKey: DND_RENDER_ID,
    width: NODE_WIDTH,
    height: NODE_HEIGHT,
  } as const

  /** 查图的meta元信息 */
  export const queryGraphMeta: NsGraphCmd.GraphMeta.IArgs['graphMetaService'] = async args => {
    console.log('queryMeta', args)
    return { ...args, flowId: args.meta.flowId }
  }
  /** 加载图数据的api */
  export const loadGraphData = async (meta: NsGraph.IGraphMeta) => { 
    let response = await request.get(applicationPipeLineUrl, {params: {id: meta.flowId}});
    let data = response.data as ApplicationPipeRelation;
    console.log('loadGraphData', response);
    const nodes: NsGraph.INodeConfig[] = data.vertex.map((vertx) => {
      return {
        ...NODE_COMMON_PROPS,
        id: `${vertx.id}`,
        label: vertx.pipeName,
        data: {pipeClass: vertx.pipeClass, pipeParams: vertx.params},
        ports: [
          {
            id: `${vertx.id}-input`,
            type: NsGraph.AnchorType.INPUT,
            group: NsGraph.AnchorGroup.TOP,
            tooltip: '输入桩',
          },
          {
            id: `${vertx.id}-out`,
            type: NsGraph.AnchorType.OUTPUT,
            group: NsGraph.AnchorGroup.BOTTOM,
            tooltip: '输出桩',
          },
        ] as NsGraph.INodeAnchor[],
      }
    })
    const edges: NsGraph.IEdgeConfig[] = data.edges.map((edge) => {
      return {
        id: uuidv4(),
        source: `${edge.startVertex}`,
        target: `${edge.endVertex}`,
        sourcePortId: `${edge.startVertex}-out`,
        targetPortId: `${edge.endVertex}-input`
      }
    })
    return { nodes, edges }
  }
  /** 保存图数据的api */
  export const saveGraphData: NsGraphCmd.SaveGraphData.IArgs['saveGraphDataService'] = async (
    meta: NsGraph.IGraphMeta,
    graphData: NsGraph.IGraphData,
  ) => {
    let edges = graphData.edges.map((edge) => {
      return {
        startVertex: edge.source,
        endVertex: edge.target,
        appId: Number(meta.flowId)
      } as ApplicationPipeEdge;
    })
    let vertex = graphData.nodes.map((node) => {
      return {
        id: node.id,
        pipeClass: node.data.pipeClass,
        params: node.data.pipeParams,
        appId: Number(meta.flowId)
      } as ApplicationPipeVertex
    })
    let relation = {
      appId: Number(meta.flowId),
      vertex: vertex,
      edges: edges
    } as ApplicationPipeRelation
    console.log(relation);
    await request.post(putPipeLineUrl, {
      data: relation
    })
  }
  /** 部署图数据的api */
  export const deployDagService: NsDeployDagCmd.IDeployDagService = async (
    meta: NsGraph.IGraphMeta,
    graphData: NsGraph.IGraphData,
  ) => {
    console.log('deployService api', meta, graphData)
    return {
      success: true,
      data: graphData,
    }
  }

  /** 添加节点api */
  export const addNode: NsNodeCmd.AddNode.IArgs['createNodeService'] = async (
    args: NsNodeCmd.AddNode.IArgs,
  ) => {
    console.info('addNode service running, add node:', args)
    const portItems = [
      {
        type: NsGraph.AnchorType.INPUT,
        group: NsGraph.AnchorGroup.TOP,
        tooltip: '输入桩',
      },
      {
        type: NsGraph.AnchorType.OUTPUT,
        group: NsGraph.AnchorGroup.BOTTOM,
        tooltip: '输出桩',
      },
    ] as NsGraph.INodeAnchor[]

    const { id, ports = portItems, groupChildren } = args.nodeConfig
    const nodeId = id || uuidv4()
    /** 这里添加连线桩 */
    const node: NsNodeCmd.AddNode.IArgs['nodeConfig'] = {
      ...NODE_COMMON_PROPS,
      ...args.nodeConfig,
      id: nodeId,
      ports: (ports as NsGraph.INodeAnchor[]).map(port => {
        return { ...port, id: uuidv4() }
      }),
    }
    /** group没有链接桩 */
    if (groupChildren && groupChildren.length) {
      node.ports = []
    }
    return node
  }

  /** 更新节点 name，可能依赖接口判断是否重名，返回空字符串时，不更新 */
  export const renameNode: NsRenameNodeCmd.IUpdateNodeNameService = async (
    name,
    node,
    graphMeta,
  ) => {
    console.log('rename node', node, name, graphMeta)
    return { err: null, nodeName: name }
  }

  /** 删除节点的api */
  export const delNode: NsNodeCmd.DelNode.IArgs['deleteNodeService'] = async args => {
    console.info('delNode service running, del node:', args.nodeConfig.id)
    return true
  }

  /** 添加边的api */
  export const addEdge: NsEdgeCmd.AddEdge.IArgs['createEdgeService'] = async args => {
    console.info('addEdge service running, add edge:', args)
    const { edgeConfig } = args
    return {
      ...edgeConfig,
      id: uuidv4(),
    }
  }

  /** 删除边的api */
  export const delEdge: NsEdgeCmd.DelEdge.IArgs['deleteEdgeService'] = async args => {
    console.info('delEdge service running, del edge:', args)
    return true
  }

  let runningNodeId = 0
  const statusMap = {} as NsGraphStatusCommand.IStatusInfo['statusMap']
  let graphStatus: NsGraphStatusCommand.StatusEnum = NsGraphStatusCommand.StatusEnum.DEFAULT
  export const graphStatusService: NsGraphStatusCommand.IArgs['graphStatusService'] = async () => {
    if (runningNodeId < 4) {
      statusMap[`node${runningNodeId}`] = { status: NsGraphStatusCommand.StatusEnum.SUCCESS }
      statusMap[`node${runningNodeId + 1}`] = { status: NsGraphStatusCommand.StatusEnum.PROCESSING }
      runningNodeId += 1
      graphStatus = NsGraphStatusCommand.StatusEnum.PROCESSING
    } else {
      runningNodeId = 0
      statusMap.node4 = { status: NsGraphStatusCommand.StatusEnum.SUCCESS }
      graphStatus = NsGraphStatusCommand.StatusEnum.SUCCESS
    }
    return {
      graphStatus: graphStatus,
      statusMap: statusMap,
    }
  }
  export const stopGraphStatusService: NsGraphStatusCommand.IArgs['graphStatusService'] =
    async () => {
      Object.entries(statusMap).forEach(([, val]) => {
        const { status } = val as { status: NsGraphStatusCommand.StatusEnum }
        if (status === NsGraphStatusCommand.StatusEnum.PROCESSING) {
          val.status = NsGraphStatusCommand.StatusEnum.ERROR
        }
      })
      return {
        graphStatus: NsGraphStatusCommand.StatusEnum.ERROR,
        statusMap: statusMap,
      }
    }
}
