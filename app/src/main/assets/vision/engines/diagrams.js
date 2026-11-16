(function () {
  const V = window.KavyaVision;

  // 7. MERMAID.JS
  V.registerRenderer('mermaid_js', {
    _state: null,
    async mount(stage, payload) {
      await V.loadScriptOnce('/assets/vision/vendor/mermaid.bundle.mjs');
      V.emitReady('mermaid_js', 'Mermaid 12.3.0');
      const mermaid = window.mermaid;
      let theme = payload.theme === 'light' ? 'default' : 'dark';
      const source = String(payload.source || 'flowchart TD\nA[Input]-->B[Output]');

      const wrapper = document.createElement('div');
      wrapper.style.cssText = 'width:100%;height:100%;overflow:auto;display:flex;align-items:center;justify-content:center;padding:16px;';
      stage.appendChild(wrapper);

      let scale = 1.0;
      const renderDiagram = async () => {
        mermaid.initialize({ startOnLoad: false, theme, securityLevel: 'strict' });
        const id = 'mmd_' + Date.now();
        const { svg } = await mermaid.render(id, source);
        wrapper.innerHTML = svg;
        const svgEl = wrapper.querySelector('svg');
        if (svgEl) {
          svgEl.style.maxWidth = '100%';
          svgEl.style.transform = `scale(${scale})`;
          svgEl.style.transformOrigin = 'center center';
        }
      };

      await renderDiagram();
      V.setHudButtons([
        { label: 'Zoom +', onClick: () => { scale = Math.min(scale + 0.2, 2.4); const s = wrapper.querySelector('svg'); if (s) s.style.transform = `scale(${scale})`; } },
        { label: 'Zoom -', onClick: () => { scale = Math.max(scale - 0.2, 0.5); const s = wrapper.querySelector('svg'); if (s) s.style.transform = `scale(${scale})`; } },
        { label: 'Theme', onClick: () => { theme = theme === 'dark' ? 'default' : 'dark'; renderDiagram(); } }
      ]);

      this._state = { wrapper, source };
      V.emitComplete('mermaid_js', { lines: source.split('\n').length });

      return () => {
        this._state = null;
      };
    },
    exportFormat(fmt) {
      if (!this._state) return;
      if (fmt === 'MERMAID_MMD') {
        V.emitExport('mermaid_js', 'MERMAID_MMD', this._state.source, false);
        return;
      }
      const svg = this._state.wrapper.querySelector('svg');
      if (svg && (fmt === 'SVG' || fmt === 'PNG')) V.svgExport(svg, 'mermaid_js', fmt);
    }
  });

  // 8. REACT FLOW
  V.registerRenderer('react_flow', {
    _state: null,
    async mount(stage, payload) {
      V.loadCssOnce('/assets/vision/vendor/reactflow.css');
      await V.loadScriptOnce('/assets/vision/vendor/reactflow.bundle.mjs');
      V.emitReady('react_flow', 'React Flow 13.0.0');

      const React = window.React;
      const ReactDOMClient = window.ReactDOMClient;
      const RF = window.KavyaReactFlow;
      const mountEl = document.createElement('div');
      mountEl.style.cssText = 'width:100%;height:100%;background:#070B12;';
      stage.appendChild(mountEl);

      const initialNodes = (Array.isArray(payload.nodes) ? payload.nodes : []).map((n, i) => ({
        id: String(n.id || 'n' + i),
        position: n.position || { x: 40 + (i % 3) * 210, y: 60 + Math.floor(i / 3) * 120 },
        data: { label: n.data?.label || n.label || ('Node ' + (i + 1)) },
        style: { background: '#0F172A', color: '#E2E8F0', border: '1px solid #38BDF8', borderRadius: 10, padding: '8px 12px', fontSize: 12 }
      }));
      const initialEdges = (Array.isArray(payload.edges) ? payload.edges : []).map((e, i) => ({
        id: String(e.id || 'e' + i),
        source: String(e.source),
        target: String(e.target),
        label: e.label || '',
        animated: Boolean(e.animated)
      }));

      let latestNodes = initialNodes;
      let latestEdges = initialEdges;

      function FlowApp() {
        const [nodes, setNodes] = React.useState(initialNodes);
        const [edges, setEdges] = React.useState(initialEdges);
        latestNodes = nodes;
        latestEdges = edges;

        const onNodesChange = React.useCallback((chs) => {
          setNodes((nds) => {
            const next = RF.applyNodeChanges(chs, nds);
            latestNodes = next;
            return next;
          });
        }, []);

        const onEdgesChange = React.useCallback((chs) => {
          setEdges((eds) => {
            const next = RF.applyEdgeChanges(chs, eds);
            latestEdges = next;
            return next;
          });
        }, []);

        const onConnect = React.useCallback((params) => {
          setEdges((eds) => {
            const next = RF.addEdge({ ...params, animated: true }, eds);
            V.emitMutated('react_flow', { ...payload, nodes: latestNodes, edges: next }, 'Connected edge');
            return next;
          });
        }, []);

        React.useEffect(() => {
          window.__kavyaAddFlowNode = () => {
            setNodes((nds) => {
              const idx = nds.length + 1;
              const next = [...nds, {
                id: 'n' + Date.now(),
                position: { x: 80 + (idx % 3) * 170, y: 80 + Math.floor(idx / 3) * 100 },
                data: { label: 'Node ' + idx },
                style: { background: '#0F172A', color: '#E2E8F0', border: '1px solid #10B981', borderRadius: 10, padding: '8px 12px', fontSize: 12 }
              }];
              V.emitMutated('react_flow', { ...payload, nodes: next, edges: latestEdges }, 'Added node');
              return next;
            });
          };
          window.__kavyaAutoLayoutFlow = () => {
            setNodes((nds) => {
              const next = nds.map((n, i) => ({
                ...n,
                position: { x: 50 + (i % 2) * 240, y: 60 + Math.floor(i / 2) * 120 }
              }));
              V.emitMutated('react_flow', { ...payload, nodes: next, edges: latestEdges }, 'Auto-layout');
              return next;
            });
          };
        }, []);

        return React.createElement(
          RF.ReactFlow,
          { nodes, edges, onNodesChange, onEdgesChange, onConnect, fitView: true, colorMode: 'dark' },
          React.createElement(RF.Background, { color: '#1E293B', gap: 18 }),
          React.createElement(RF.MiniMap, { style: { background: '#0B1120', width: 96, height: 64 } }),
          React.createElement(RF.Controls, null)
        );
      }

      const root = ReactDOMClient.createRoot(mountEl);
      root.render(React.createElement(FlowApp));

      V.setHudButtons([
        { label: '+ Add Node', onClick: () => window.__kavyaAddFlowNode && window.__kavyaAddFlowNode() },
        { label: 'Auto Layout', onClick: () => window.__kavyaAutoLayoutFlow && window.__kavyaAutoLayoutFlow() }
      ]);

      this._state = { getPayload: () => ({ ...payload, nodes: latestNodes, edges: latestEdges }), mountEl };
      V.emitComplete('react_flow', { nodes: initialNodes.length, edges: initialEdges.length });

      return () => {
        root.unmount();
        this._state = null;
      };
    },
    exportFormat(fmt) {
      if (!this._state) return;
      if (fmt === 'JSON') {
        V.emitExport('react_flow', 'JSON', JSON.stringify(this._state.getPayload(), null, 2), false);
      } else if (fmt === 'SVG' || fmt === 'PNG') {
        // Export clean architectural SVG snapshot from live node/edge coordinates
        const st = this._state.getPayload();
        const ns = 'http://www.w3.org/2000/svg';
        const svg = document.createElementNS(ns, 'svg');
        svg.setAttribute('viewBox', '0 0 840 480');
        svg.setAttribute('width', '840');
        svg.setAttribute('height', '480');
        const bg = document.createElementNS(ns, 'rect');
        bg.setAttribute('width', '840'); bg.setAttribute('height', '480'); bg.setAttribute('fill', '#070B12');
        svg.appendChild(bg);
        const byId = {};
        (st.nodes || []).forEach((n) => { byId[n.id] = n.position || { x: 80, y: 80 }; });
        (st.edges || []).forEach((e) => {
          const p1 = byId[e.source]; const p2 = byId[e.target];
          if (p1 && p2) {
            const line = document.createElementNS(ns, 'line');
            line.setAttribute('x1', p1.x + 80); line.setAttribute('y1', p1.y + 22);
            line.setAttribute('x2', p2.x + 80); line.setAttribute('y2', p2.y + 22);
            line.setAttribute('stroke', '#38BDF8'); line.setAttribute('stroke-width', '2');
            svg.appendChild(line);
          }
        });
        (st.nodes || []).forEach((n) => {
          const g = document.createElementNS(ns, 'g');
          const r = document.createElementNS(ns, 'rect');
          r.setAttribute('x', n.position.x); r.setAttribute('y', n.position.y);
          r.setAttribute('width', '160'); r.setAttribute('height', '44'); r.setAttribute('rx', '8');
          r.setAttribute('fill', '#0F172A'); r.setAttribute('stroke', '#38BDF8');
          const t = document.createElementNS(ns, 'text');
          t.setAttribute('x', n.position.x + 80); t.setAttribute('y', n.position.y + 26);
          t.setAttribute('text-anchor', 'middle'); t.setAttribute('fill', '#E2E8F0'); t.setAttribute('font-size', '12');
          t.textContent = n.data?.label || n.id;
          g.appendChild(r); g.appendChild(t); svg.appendChild(g);
        });
        V.svgExport(svg, 'react_flow', fmt);
      }
    }
  });

  // 9. EXCALIDRAW
  V.registerRenderer('excalidraw', {
    _state: null,
    async mount(stage, payload) {
      window.EXCALIDRAW_ASSET_PATH = '/assets/vision/vendor/excalidraw-assets/';
      V.loadCssOnce('/assets/vision/vendor/excalidraw.css');
      await V.loadScriptOnce('/assets/vision/vendor/excalidraw.bundle.mjs');
      V.emitReady('excalidraw', 'Excalidraw 0.18.1');

      const React = window.React;
      const ReactDOMClient = window.ReactDOMClient;
      const EX = window.KavyaExcalidraw;

      const mountEl = document.createElement('div');
      mountEl.style.cssText = 'width:100%;height:100%;';
      stage.appendChild(mountEl);

      const rawEls = (Array.isArray(payload.elements) ? payload.elements : []).map((el) => {
        if (el.versionNonce !== undefined) return el;
        return {
          type: el.type || 'rectangle',
          id: el.id,
          x: Number(el.x || 60),
          y: Number(el.y || 60),
          width: Number(el.width || 160),
          height: Number(el.height || 80),
          strokeColor: el.strokeColor || '#38BDF8',
          backgroundColor: el.backgroundColor || 'transparent',
          ...(el.labelText ? { label: { text: el.labelText, fontSize: 16 } } : {})
        };
      });
      const elements = EX.convertToExcalidrawElements ? EX.convertToExcalidrawElements(rawEls) : rawEls;
      let apiRef = null;
      let latestScene = elements;

      function WhiteboardApp() {
        return React.createElement(EX.Excalidraw, {
          theme: payload.theme === 'light' ? 'light' : 'dark',
          initialData: { elements, appState: { viewBackgroundColor: '#070B12' } },
          excalidrawAPI: (api) => { apiRef = api; },
          onChange: (els) => { latestScene = els; }
        });
      }

      const root = ReactDOMClient.createRoot(mountEl);
      root.render(React.createElement(WhiteboardApp));

      V.setHudButtons([
        {
          label: 'Save Sketch',
          onClick: () => V.emitMutated('excalidraw', { ...payload, elements: latestScene }, 'Whiteboard snapshot')
        }
      ]);

      this._state = { EX, getApi: () => apiRef, getElements: () => latestScene, payload };
      V.emitComplete('excalidraw', { elements: elements.length });

      return () => {
        root.unmount();
        this._state = null;
      };
    },
    async exportFormat(fmt) {
      if (!this._state) return;
      const api = this._state.getApi();
      const els = api ? api.getSceneElements() : this._state.getElements();
      const appState = api ? api.getAppState() : { viewBackgroundColor: '#070B12' };
      const files = api ? api.getFiles() : {};
      if (fmt === 'EXCALIDRAW') {
        V.emitExport('excalidraw', 'EXCALIDRAW', JSON.stringify({
          type: 'excalidraw',
          version: 2,
          source: 'kavya-vision',
          elements: els,
          appState: { viewBackgroundColor: appState.viewBackgroundColor },
          files
        }, null, 2), false);
      } else if (fmt === 'SVG' && this._state.EX.exportToSvg) {
        const svg = await this._state.EX.exportToSvg({ elements: els, appState, files });
        V.svgExport(svg, 'excalidraw', 'SVG');
      } else if (fmt === 'PNG' && this._state.EX.exportToBlob) {
        const blob = await this._state.EX.exportToBlob({ elements: els, appState, files, mimeType: 'image/png' });
        const reader = new FileReader();
        reader.onloadend = () => V.emitExport('excalidraw', 'PNG', reader.result, true);
        reader.readAsDataURL(blob);
      }
    }
  });
})();
