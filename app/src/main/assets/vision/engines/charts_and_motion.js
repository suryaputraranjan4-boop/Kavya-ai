(function () {
  const V = window.KavyaVision;

  // 10. D3.JS
  V.registerRenderer('d3_js', {
    _state: null,
    async mount(stage, payload) {
      await V.loadScriptOnce('/assets/vision/vendor/d3.bundle.mjs');
      V.emitReady('d3_js', 'D3.js 7.9.2');
      const d3 = window.d3;

      const w = stage.clientWidth || 720;
      const h = stage.clientHeight || 460;
      const svg = d3.select(stage)
        .append('svg')
        .attr('width', '100%')
        .attr('height', '100%')
        .attr('viewBox', `0 0 ${w} ${h}`)
        .style('background', '#070B12');

      const g = svg.append('g');
      svg.call(d3.zoom().scaleExtent([0.35, 3.5]).on('zoom', (ev) => g.attr('transform', ev.transform)));

      const nodes = (Array.isArray(payload.nodes) ? payload.nodes : [{ id: 'Center', group: 1, value: 24 }]).map((n) => ({ ...n }));
      const links = (Array.isArray(payload.links) ? payload.links : []).map((l) => ({ ...l }));
      const palette = ['#00F5FF', '#10B981', '#F59E0B', '#38BDF8', '#22D3EE'];

      const sim = d3.forceSimulation(nodes)
        .force('link', d3.forceLink(links).id((d) => d.id).distance(95))
        .force('charge', d3.forceManyBody().strength(-240))
        .force('center', d3.forceCenter(w / 2, h / 2));

      const link = g.append('g')
        .attr('stroke', '#334155')
        .selectAll('line')
        .data(links)
        .join('line')
        .attr('stroke-width', (d) => Math.max(1.5, Math.min(4, Number(d.weight || 2))));

      const node = g.append('g')
        .selectAll('g')
        .data(nodes)
        .join('g')
        .call(d3.drag()
          .on('start', (ev, d) => { if (!ev.active) sim.alphaTarget(0.3).restart(); d.fx = d.x; d.fy = d.y; })
          .on('drag', (ev, d) => { d.fx = ev.x; d.fy = ev.y; })
          .on('end', (ev, d) => { if (!ev.active) sim.alphaTarget(0); d.fx = null; d.fy = null; })
        );

      node.append('circle')
        .attr('r', (d) => Math.max(12, Math.min(28, Number(d.value || 18))))
        .attr('fill', (d) => palette[(Number(d.group || 1) - 1) % palette.length])
        .attr('stroke', '#070B12')
        .attr('stroke-width', 2);

      node.append('text')
        .attr('dy', 4)
        .attr('x', (d) => Math.max(15, Math.min(31, Number(d.value || 18) + 4)))
        .attr('fill', '#E2E8F0')
        .attr('font-size', 11)
        .text((d) => d.id);

      sim.on('tick', () => {
        link
          .attr('x1', (d) => d.source.x).attr('y1', (d) => d.source.y)
          .attr('x2', (d) => d.target.x).attr('y2', (d) => d.target.y);
        node.attr('transform', (d) => `translate(${d.x},${d.y})`);
      });

      V.setHudButtons([
        { label: 'Reheat Physics', onClick: () => sim.alpha(0.9).restart() }
      ]);

      this._state = { svgEl: svg.node(), payload };
      V.emitComplete('d3_js', { nodes: nodes.length, links: links.length });

      return () => {
        sim.stop();
        this._state = null;
      };
    },
    exportFormat(fmt) {
      if (!this._state) return;
      if (fmt === 'CSV') {
        const rows = ['id,group,value', ...(this._state.payload.nodes || []).map((n) => `${n.id},${n.group || 1},${n.value || 10}`)];
        V.emitExport('d3_js', 'CSV', rows.join('\n'), false);
        return;
      }
      if (fmt === 'SVG' || fmt === 'PNG') V.svgExport(this._state.svgEl, 'd3_js', fmt);
    }
  });

  // 11. PLOTLY.JS
  V.registerRenderer('plotly_js', {
    _state: null,
    async mount(stage, payload) {
      await V.loadScriptOnce('/assets/vision/vendor/plotly.bundle.js');
      V.emitReady('plotly_js', 'Plotly.js 4.3.0');
      const Plotly = window.Plotly;

      const plotDiv = document.createElement('div');
      plotDiv.style.cssText = 'width:100%;height:100%;';
      stage.appendChild(plotDiv);

      let traces = Array.isArray(payload.data) ? payload.data : [];
      if (payload.mode === 'math_surface' && traces.length === 0) {
        const min = Number(payload.rangeMin ?? -6);
        const max = Number(payload.rangeMax ?? 6);
        const steps = Math.min(Number(payload.steps ?? 26), V.getQuality() === 'low' ? 18 : 32);
        const xs = [];
        const zs = [];
        for (let i = 0; i <= steps; i++) {
          const x = min + ((max - min) * i) / steps;
          xs.push(x);
          const row = [];
          for (let j = 0; j <= steps; j++) {
            const y = min + ((max - min) * j) / steps;
            const r = Math.sqrt(x * x + y * y);
            row.push(r === 0 ? 1 : Math.sin(r));
          }
          zs.push(row);
        }
        traces = [{ type: 'surface', x: xs, y: xs, z: zs, colorscale: 'Viridis' }];
      }

      const layout = {
        paper_bgcolor: '#070B12',
        plot_bgcolor: '#0B1120',
        font: { color: '#E2E8F0', size: 11 },
        margin: { l: 42, r: 20, t: 42, b: 42 },
        ...(payload.layout || {}),
        title: payload.title || payload.layout?.title || 'Plotly Visualization'
      };

      await Plotly.newPlot(plotDiv, traces, layout, { responsive: true, displaylogo: false });
      this._state = { Plotly, plotDiv, traces, layout };
      V.emitComplete('plotly_js', { traceCount: traces.length });

      return () => {
        Plotly.purge(plotDiv);
        this._state = null;
      };
    },
    async exportFormat(fmt) {
      if (!this._state) return;
      if (fmt === 'PLOTLY_JSON') {
        V.emitExport('plotly_js', 'PLOTLY_JSON', JSON.stringify({ data: this._state.traces, layout: this._state.layout }, null, 2), false);
      } else if (fmt === 'PNG' || fmt === 'SVG') {
        const dataUrl = await this._state.Plotly.toImage(this._state.plotDiv, { format: fmt.toLowerCase(), width: 900, height: 600 });
        V.emitExport('plotly_js', fmt, fmt === 'SVG' ? decodeURIComponent(dataUrl.split(',')[1] || '') : dataUrl, fmt === 'PNG');
      }
    }
  });

  // 12. APACHE ECHARTS
  V.registerRenderer('apache_echarts', {
    _state: null,
    async mount(stage, payload) {
      await V.loadScriptOnce('/assets/vision/vendor/echarts.bundle.js');
      V.emitReady('apache_echarts', 'ECharts 6.1.0');
      const echarts = window.echarts;

      const container = document.createElement('div');
      container.style.cssText = 'width:100%;height:100%;';
      stage.appendChild(container);

      const chart = echarts.init(container, 'dark', { renderer: 'canvas' });
      const option = buildEChartsOption(payload);
      chart.setOption(option);

      const onResize = () => chart.resize();
      window.addEventListener('resize', onResize);

      this._state = { chart, option, payload };
      V.emitComplete('apache_echarts', { chartType: payload.chartType || 'radar' });

      return () => {
        window.removeEventListener('resize', onResize);
        chart.dispose();
        this._state = null;
      };
    },
    exportFormat(fmt) {
      if (!this._state) return;
      if (fmt === 'PNG') {
        const url = this._state.chart.getDataURL({ type: 'png', backgroundColor: '#070B12', pixelRatio: 2 });
        V.emitExport('apache_echarts', 'PNG', url, true);
      } else if (fmt === 'SVG') {
        const svgUrl = this._state.chart.getDataURL({ type: 'svg' });
        V.emitExport('apache_echarts', 'SVG', svgUrl, false);
      }
    }
  });

  function buildEChartsOption(payload) {
    if (payload.option && typeof payload.option === 'object') return payload.option;
    const type = String(payload.chartType || 'radar').toLowerCase();
    if (type === 'radar') {
      const indicators = Array.isArray(payload.indicators) ? payload.indicators : [{ name: 'Metric', max: 100 }];
      const seriesData = (Array.isArray(payload.series) ? payload.series : []).map((s) => ({
        name: s.name || 'Series',
        value: s.values || []
      }));
      return {
        backgroundColor: '#070B12',
        title: { text: payload.title || 'Radar Chart', left: 'center', top: 8, textStyle: { fontSize: 13 } },
        tooltip: {},
        legend: { bottom: 6, data: seriesData.map((s) => s.name) },
        radar: { indicator: indicators, radius: '58%' },
        series: [{ type: 'radar', data: seriesData }]
      };
    }
    return {
      backgroundColor: '#070B12',
      title: { text: payload.title || 'ECharts Visual', left: 'center', top: 8 },
      tooltip: { trigger: 'axis' },
      dataZoom: [{ type: 'inside' }],
      xAxis: { type: 'category', data: payload.xAxis || ['A', 'B', 'C'] },
      yAxis: { type: 'value' },
      series: [{ type: type === 'line' ? 'line' : 'bar', data: payload.data || [0, 0, 0], itemStyle: { color: '#00E5FF' } }]
    };
  }

  // 13. 3D FORCE GRAPH
  V.registerRenderer('force_graph_3d', {
    _state: null,
    async mount(stage, payload) {
      await V.loadScriptOnce('/assets/vision/vendor/force-graph-3d.bundle.mjs');
      V.emitReady('force_graph_3d', '3d-force-graph 1.80.2');

      const maxNodes = V.getQuality() === 'low' ? 60 : 180;
      const rawNodes = (Array.isArray(payload.nodes) ? payload.nodes : []).slice(0, maxNodes);
      const idSet = new Set(rawNodes.map((n) => n.id));
      const rawLinks = (Array.isArray(payload.links) ? payload.links : []).filter((l) => idSet.has(l.source) && idSet.has(l.target));

      const graphDiv = document.createElement('div');
      graphDiv.style.cssText = 'width:100%;height:100%;';
      stage.appendChild(graphDiv);

      let showParticles = !V.isReducedMotion();
      const fg = window.ForceGraph3D({ controlType: 'orbit', rendererConfig: { preserveDrawingBuffer: true } })(graphDiv)
        .backgroundColor('#070B12')
        .graphData({ nodes: rawNodes, links: rawLinks })
        .nodeLabel((n) => n.id)
        .nodeAutoColorBy('group')
        .linkDirectionalParticles(showParticles ? 2 : 0)
        .linkDirectionalParticleSpeed(0.006)
        .onNodeClick((node) => {
          const dist = 45;
          const ratio = 1 + dist / Math.hypot(node.x || 1, node.y || 1, node.z || 1);
          fg.cameraPosition(
            { x: (node.x || 0) * ratio, y: (node.y || 0) * ratio, z: (node.z || 0) * ratio },
            node,
            800
          );
        });

      V.setHudButtons([
        {
          label: 'Particles',
          active: showParticles,
          onClick: (btn) => {
            showParticles = !showParticles;
            btn.classList.toggle('active', showParticles);
            fg.linkDirectionalParticles(showParticles ? 2 : 0);
          }
        },
        { label: 'Fit View', onClick: () => fg.zoomToFit(600, 40) }
      ]);

      this._state = { fg, graphDiv };
      V.emitComplete('force_graph_3d', { nodes: rawNodes.length, links: rawLinks.length });

      return () => {
        if (typeof fg._destructor === 'function') fg._destructor();
        this._state = null;
      };
    },
    exportFormat(fmt) {
      if (fmt === 'PNG' && this._state) {
        const canvas = this._state.graphDiv.querySelector('canvas');
        if (canvas) V.canvasExportPng(canvas, 'force_graph_3d');
      }
    }
  });

  // 14. GSAP TIMELINE ANIMATION ENGINE
  V.registerRenderer('gsap', {
    _state: null,
    async mount(stage, payload) {
      await V.loadScriptOnce('/assets/vision/vendor/gsap.bundle.mjs');
      V.emitReady('gsap', 'GSAP 3.15.1');
      const gsap = window.gsap;

      const steps = Array.isArray(payload.steps) && payload.steps.length ? payload.steps : [
        { id: 's1', label: 'Phase 1', color: '#38BDF8', duration: 0.9 }
      ];

      const ns = 'http://www.w3.org/2000/svg';
      const svg = document.createElementNS(ns, 'svg');
      svg.setAttribute('viewBox', '0 0 800 420');
      svg.style.cssText = 'width:100%;height:100%;background:#070B12;';
      stage.appendChild(svg);

      const tl = gsap.timeline({ repeat: V.isReducedMotion() ? 0 : -1, repeatDelay: 0.8 });
      const cardWidth = Math.max(130, Math.floor(680 / Math.max(steps.length, 1)));
      steps.forEach((s, i) => {
        const x = 50 + i * (cardWidth + 16);
        const y = 150;
        const g = document.createElementNS(ns, 'g');
        g.setAttribute('id', 'gsap_step_' + i);
        const rect = document.createElementNS(ns, 'rect');
        rect.setAttribute('x', x); rect.setAttribute('y', y);
        rect.setAttribute('width', cardWidth); rect.setAttribute('height', 96);
        rect.setAttribute('rx', 14); rect.setAttribute('fill', '#0F172A');
        rect.setAttribute('stroke', s.color || '#38BDF8'); rect.setAttribute('stroke-width', '2.5');
        const txt = document.createElementNS(ns, 'text');
        txt.setAttribute('x', x + cardWidth / 2); txt.setAttribute('y', y + 52);
        txt.setAttribute('text-anchor', 'middle'); txt.setAttribute('fill', '#E2E8F0');
        txt.setAttribute('font-size', '12');
        txt.textContent = String(s.label || 'Step ' + (i + 1)).slice(0, 22);
        g.appendChild(rect);
        g.appendChild(txt);
        svg.appendChild(g);

        if (!V.isReducedMotion()) {
          tl.fromTo(g, { opacity: 0.28, y: 24 }, { opacity: 1, y: 0, duration: Number(s.duration || 0.7), ease: 'power2.out' });
        }
      });

      if (V.isReducedMotion()) tl.progress(1);

      V.setHudButtons([
        { label: 'Play', onClick: () => tl.play() },
        { label: 'Pause', onClick: () => tl.pause() },
        { label: 'Restart', onClick: () => tl.restart() },
        { label: '1.5x', onClick: () => tl.timeScale(1.5) },
        { label: '1.0x', onClick: () => tl.timeScale(1.0) }
      ]);

      this._state = { tl, svg };
      V.emitComplete('gsap', { stepCount: steps.length });

      return () => {
        tl.kill();
        this._state = null;
      };
    },
    exportFormat(fmt) {
      if (this._state && (fmt === 'SVG' || fmt === 'PNG')) V.svgExport(this._state.svg, 'gsap', fmt);
    }
  });
})();
